package com.conveyal.gtfs;

import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.s3.model.AmazonS3Exception;
import com.amazonaws.services.s3.model.S3Object;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.cache.RemovalListener;
import com.google.common.io.ByteStreams;
import com.google.common.io.Files;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.function.Function;
import java.util.zip.ZipFile;

/**
 * Fast cache for GTFS feeds stored on S3.
 *
 * Depending on the application, we often want to store additional data with a GTFS feed. Thus, you can subclass this
 * class and override the processFeed function with a function that transforms a GTFSFeed object into whatever objects
 * you need. If you just need to store GTFSFeeds without any additional data, see the GTFSCache class.
 *
 * This uses a soft-values cache because (it is assumed) you do not want to have multiple copies of the same GTFS feed
 * in memory. When you are storing a reference to the original GTFS feed, it may be retrieved from the cache and held
 * by the caller for some finite amount of time. If, during that time, it is removed from the cache and requested again,
 * we would connect another GTFSFeed to the same mapdb, which seems like an ideal way to corrupt mapdbs. SoftReferences
 * prevent this as it cannot be removed if it is referenced elsewhere.
 */
public abstract class BaseGTFSCache<T extends Closeable> {
    private static final Logger LOG = LoggerFactory.getLogger(BaseGTFSCache.class);

    public final String bucket;
    public final String bucketFolder;

    public final File cacheDir;

    private static AmazonS3 s3 = null;
    private LoadingCache<String, T> cache;

    public BaseGTFSCache(String bucket, File cacheDir) {
        this(bucket, null, cacheDir);
    }

    public BaseGTFSCache(String bucket, String bucketFolder, File cacheDir) {
        this(null, bucket, bucketFolder, cacheDir);
    }

    /** If bucket is null, work offline and do not use S3 */
    public BaseGTFSCache(String awsRegion, String bucket, String bucketFolder, File cacheDir) {
        if (awsRegion == null || bucket == null) LOG.info("No AWS region/bucket specified; GTFS Cache will run locally");
        else {
            s3 = AmazonS3ClientBuilder.standard().withRegion(awsRegion).build();
            LOG.info("Using bucket {} for GTFS Cache", bucket);
        }

        this.bucket = bucket;
        this.bucketFolder = bucketFolder != null ? bucketFolder.replaceAll("\\/","") : null;

        this.cacheDir = cacheDir;

        RemovalListener<String, T> removalListener = removalNotification -> {
            try {
                LOG.info("Evicting feed {} from gtfs-cache and closing MapDB file. Reason: {}",
                        removalNotification.getKey(),
                        removalNotification.getCause());
                // Close DB to avoid leaking (off-heap allocated) memory for MapDB object cache, and MapDB corruption.
                removalNotification.getValue().close();
                // Delete local .zip file ONLY if using s3 (i.e. when 'bucket' has been set to something).
                // TODO elaborate on why we would want to do this.
                if (bucket != null) {
                    String id = removalNotification.getKey();
                    String[] extensions = {".zip"}; // used to include ".db", ".db.p" as well.  See #119
                    // delete local cache files (including zip) when feed removed from cache
                    for (String type : extensions) {
                        File file = new File(cacheDir, id + type);
                        file.delete();
                    }
                }
            } catch (Exception e) {
                throw new RuntimeException("Exception while trying to evict GTFS MapDB from cache.", e);
            }
        };
        this.cache = (LoadingCache<String, T>) CacheBuilder.newBuilder()
                // we use SoftReferenced values because we have the constraint that we don't want more than one
                // copy of a particular GTFSFeed object around; that would mean multiple MapDBs are pointing at the same
                // file, which is bad.
                //.maximumSize(x) should be less sensitive to GC, though it interacts with instance cache size of MapDB
                // itself. But is MapDB instance cache on or off heap?
                .maximumSize(20)
                // .softValues()
                .removalListener(removalListener)
                .build(new CacheLoader() {
                    public T load(Object s) throws Exception {
                        // Thanks, java, for making me use a cast here. If I put generic arguments to new CacheLoader
                        // due to type erasure it can't be sure I'm using types correctly.
                        return retrieveAndProcessFeed((String) s);
                    }
                });
    }

    public long getCurrentCacheSize() {
        return this.cache.size();
    }

    /**
     * Add a GTFS feed to this cache with the given ID. NB this is not the feed ID, because feed IDs are not
     * unique when you load multiple versions of the same feed.
     */
    public T put (String id, File feedFile) throws Exception {
        return put(id, feedFile, null);
    }

    /** Add a GTFS feed to this cache where the ID is calculated from the feed itself */
    public T put (Function<GTFSFeed, String> idGenerator, File feedFile) throws Exception {
        return put(null, feedFile, idGenerator);
    }

    // TODO rename these methods. This does a lot more than a typical Map.put method so the name gets confusing.
    private T put (String id, File feedFile, Function<GTFSFeed, String> idGenerator) throws Exception {
        // generate temporary ID to name files
        String tempId = id != null ? id : UUID.randomUUID().toString();

        // read the feed
        String cleanTempId = cleanId(tempId);
        File dbFile = new File(cacheDir, cleanTempId + ".v2.db");
        File movedFeedFile = new File(cacheDir, cleanTempId + ".zip");

        // don't copy if we're loading from a locally-cached feed
        if (!feedFile.equals(movedFeedFile)) Files.copy(feedFile, movedFeedFile);

        GTFSFeed feed = new GTFSFeed(dbFile.getAbsolutePath());
        feed.loadFromFile(new ZipFile(movedFeedFile));
        feed.findPatterns();

        if (idGenerator != null) id = idGenerator.apply(feed);

        String cleanId = cleanId(id);

        // Close the DB and flush to disk before we start moving and copying files around.
        feed.close();

        if (idGenerator != null) {
            // This mess seems to be necessary to get around Windows file locks.
            File originalZip = new File(cacheDir, cleanTempId + ".zip");
            File originalDb = new File(cacheDir, cleanTempId + ".v2.db");
            File originalDbp = new File(cacheDir, cleanTempId + ".v2.db.p");
            Files.copy(originalZip,(new File(cacheDir, cleanId + ".zip")));
            Files.copy(originalDb,(new File(cacheDir, cleanId + ".v2.db")));
            Files.copy(originalDbp,(new File(cacheDir, cleanId + ".v2.db.p")));
            originalZip.delete();
            originalDb.delete();
            originalDbp.delete();
        }

        // upload feed
        // TODO best way to do this? Should we zip the files together?
        if (bucket != null) {
            LOG.info("Writing feed to s3 cache");
            String key = bucketFolder != null ? String.join("/", bucketFolder, cleanId) : cleanId;

            // write zip to s3 if not already there
            if (!s3.doesObjectExist(bucket, key + ".zip")) {
                s3.putObject(bucket, key + ".zip", feedFile);
                LOG.info("Zip file written.");
            }
            else {
                LOG.info("Zip file already exists on s3.");
            }
            s3.putObject(bucket, key + ".v2.db", new File(cacheDir, cleanId + ".v2.db"));
            s3.putObject(bucket, key + ".v2.db.p", new File(cacheDir, cleanId + ".v2.db.p"));
            LOG.info("db files written.");
        }

        // Reopen the feed database so we can return it ready for use to the caller. Note that we do not add the feed
        // to the cache here. The returned feed is inserted automatically into the LoadingCache by the CacheLoader.
        feed = new GTFSFeed(new File(cacheDir, cleanId + ".v2.db").getAbsolutePath());
        T processed = processFeed(feed);
        return processed;
    }

    public T get (String id) {
        try {
            return cache.get(id);
        } catch (ExecutionException e) {
            LOG.error("Error loading local MapDB.", e);
            deleteLocalDBFiles(id);
            return null;
        }
    }

    public boolean containsId (String id) {
        T feed;
        try {
            feed = cache.get(id);
        } catch (Exception e) {
            return false;
        }
        return feed != null;
    }


    /** retrieve a feed from local cache or S3 */
    private T retrieveAndProcessFeed (String originalId) {
        // see if we have it cached locally
        String id = cleanId(originalId);
        String key = bucketFolder != null ? String.join("/", bucketFolder, id) : id;
        File dbFile = new File(cacheDir, id + ".v2.db");
        GTFSFeed feed;
        if (dbFile.exists()) {
            LOG.info("Processed GTFS was found cached locally");
            try {
                feed = new GTFSFeed(dbFile.getAbsolutePath());
                if (feed != null) {
                    return processFeed(feed);
                }
            } catch (Exception e) {
                LOG.warn("Error loading local MapDB.", e);
                deleteLocalDBFiles(id);
            }
        }

        if (bucket != null) {
            try {
                LOG.info("Attempting to download cached GTFS MapDB.");
                S3Object db = s3.getObject(bucket, key + ".v2.db");
                InputStream is = db.getObjectContent();
                FileOutputStream fos = new FileOutputStream(dbFile);
                ByteStreams.copy(is, fos);
                is.close();
                fos.close();

                S3Object dbp = s3.getObject(bucket, key + ".v2.db.p");
                InputStream isp = dbp.getObjectContent();
                FileOutputStream fosp = new FileOutputStream(new File(cacheDir, id + ".v2.db.p"));
                ByteStreams.copy(isp, fosp);
                isp.close();
                fosp.close();

                LOG.info("Returning processed GTFS from S3");
                feed = new GTFSFeed(dbFile.getAbsolutePath());
                if (feed != null) {
                    return processFeed(feed);
                }
            } catch (AmazonS3Exception e) {
                LOG.warn("DB file for key {} does not exist on S3.", key);
            } catch (ExecutionException | IOException e) {
                LOG.warn("Error retrieving MapDB from S3, will load from original GTFS.", e);
            }
        }
        // if we fell through to here, getting the mapdb was unsuccessful
        // grab GTFS from S3 if it is not found locally
        File feedFile = new File(cacheDir, id + ".zip");
        if (feedFile.exists()) {
            LOG.info("Loading feed from local cache directory...");
        }

        if (!feedFile.exists() && bucket != null) {
            LOG.info("Feed not found locally, downloading from S3.");
            try {
                S3Object gtfs = s3.getObject(bucket, key + ".zip");
                InputStream is = gtfs.getObjectContent();
                FileOutputStream fos = new FileOutputStream(feedFile);
                ByteStreams.copy(is, fos);
                is.close();
                fos.close();
            } catch (Exception e) {
                LOG.error("Could not download feed at s3://{}/{}.", bucket, key);
                throw new RuntimeException(e);
            }
        }

        if (feedFile.exists()) {
            // TODO this will also re-upload the original feed ZIP to S3.
            try {
                return put(originalId, feedFile);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        } else {
            LOG.warn("Feed {} not found locally", originalId);
            throw new NoSuchElementException(originalId);
        }
    }

    /** Convert a GTFSFeed into whatever this cache holds */
    protected abstract T processFeed (GTFSFeed feed);

    public abstract GTFSFeed getFeed (String id);

    private void deleteLocalDBFiles(String id) {
        String[] extensions = {".v2.db", ".v2.db.p"};
        // delete ONLY local cache db files
        for (String type : extensions) {
            File file = new File(cacheDir, id + type);
            file.delete();
        }
    }

    public static String cleanId(String id) {
        // replace all special characters with `-`, except for underscore `_`
        return id.replaceAll("[^A-Za-z0-9_]", "-");
    }
}
