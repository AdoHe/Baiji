package com.xqbase.baiji.generic;

import com.xqbase.baiji.exceptions.BaijiRuntimeException;
import com.xqbase.baiji.schema.RecordSchema;
import com.xqbase.baiji.schema.Schema;
import com.xqbase.baiji.schema.SchemaType;

/**
 * Utilities for generic Java data.
 *
 * @author Tony He
 */
public class GenericData {

    private static final GenericData INSTANCE = new GenericData();

    private final ClassLoader classLoader;

    /** Return the singleton instance. */
    public static GenericData get() {
        return INSTANCE;
    }

    /** For subclass. Applications normally use {@link GenericData#get()}. */
    public GenericData() {
        this(null);
    }

    /** For subclass. Applications normally use {@link GenericData#get()}. */
    public GenericData(ClassLoader classLoader) {
        this.classLoader = (classLoader != null)
                ? classLoader
                : getClass().getClassLoader();
    }

    public Object getField(Object record, String name, int position) {
        return ((IndexedRecord) record).get(position);
    }

    /**
     * A default implementation of {@link com.xqbase.baiji.generic.GenericRecord}.
     */
    private static class Record implements GenericRecord, Comparable<Record> {

        private final RecordSchema recordSchema;
        private final Object[] values;

        public Record(RecordSchema recordSchema) {
            if (null == recordSchema || !SchemaType.RECORD.equals(recordSchema.getType())) {
                throw new BaijiRuntimeException("schema type is not record: " + recordSchema);
            }
            this.recordSchema = recordSchema;
            this.values = new Object[recordSchema.getFields().size()];
        }

        @Override
        public int compareTo(Record o) {
            return 0;
        }

        @Override
        public void put(String key, Object v) {

        }

        @Override
        public Object get(String key) {
            return null;
        }

        @Override
        public void put(int i, Object v) {

        }

        @Override
        public Object get(int i) {
            return null;
        }

        @Override
        public Schema getSchema() {
            return null;
        }
    }

    public Object newRecord(Object reuse, RecordSchema schema) {
        if (reuse instanceof IndexedRecord) {
            IndexedRecord record = (IndexedRecord) reuse;
            if (record.getSchema().equals(schema)) {
                return reuse;
            }
        }

        return new Record(schema);
    }
}
