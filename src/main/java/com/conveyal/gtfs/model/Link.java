package com.conveyal.gtfs.model;

import com.conveyal.gtfs.GTFSFeed;

import java.io.IOException;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Iterator;

public class Link extends Entity {
    public String from_stop_id;
    public String to_stop_id;
    public int duration;
    public int start_time;
    public int end_time;

    /**
     * Sets the parameters for a prepared statement following the parameter order defined in
     * {@link com.conveyal.gtfs.loader.Table#TRANSFERS}. JDBC prepared statement parameters use a one-based index.
     */
    @Override
    public void setStatementParameters(PreparedStatement statement, boolean setDefaultId) throws SQLException {
        int oneBasedIndex = 1;
        if (!setDefaultId) statement.setInt(oneBasedIndex++, id);
        statement.setString(oneBasedIndex++, from_stop_id);
        statement.setString(oneBasedIndex++, to_stop_id);
        setIntParameter(statement, oneBasedIndex++, duration);
        setIntParameter(statement, oneBasedIndex++, start_time);
        setIntParameter(statement, oneBasedIndex++, end_time);
    }

    public static class Loader extends Entity.Loader<Transfer> {

        public Loader(GTFSFeed feed) {
            super(feed, "links");
        }

        @Override
        protected boolean isRequired() {
            return false;
        }

        @Override
        public void loadOneRow() throws IOException {
            Link tr = new Link();
            tr.id = row + 1; // offset line number by 1 to account for 0-based row index
            tr.from_stop_id      = getStringField("from_stop_id", true);
            tr.to_stop_id        = getStringField("to_stop_id", true);
            tr.duration          = getIntField("duration", false, 0, Integer.MAX_VALUE);
            tr.start_time        = getTimeField("start_time", true);
            tr.end_time          = getTimeField("end_time", true);

            tr.feed = feed;
            feed.links.put(Long.toString(row), tr);
        }

    }

    public static class Writer extends Entity.Writer<Link> {
        public Writer (GTFSFeed feed) {
            super(feed, "links");
        }

        @Override
        protected void writeHeaders() throws IOException {
            writer.writeRecord(new String[] {"from_stop_id", "to_stop_id", "duration", "start_time", "end_time"});
        }

        @Override
        protected void writeOneRow(Link t) throws IOException {
            writeStringField(t.from_stop_id);
            writeStringField(t.to_stop_id);
            writeIntField(t.duration);
            writeIntField(t.start_time);
            writeIntField(t.end_time);
            endRecord();
        }

        @Override
        protected Iterator<Link> iterator() {
            return feed.links.values().iterator();
        }


    }
}
