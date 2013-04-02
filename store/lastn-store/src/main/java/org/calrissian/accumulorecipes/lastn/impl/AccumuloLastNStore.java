package org.calrissian.accumulorecipes.lastn.impl;


import org.apache.accumulo.core.client.*;
import org.apache.accumulo.core.client.admin.TableOperations;
import org.apache.accumulo.core.data.Mutation;
import org.apache.accumulo.core.data.Range;
import org.apache.accumulo.core.data.Value;
import org.apache.accumulo.core.iterators.IteratorUtil;
import org.apache.accumulo.core.security.Authorizations;
import org.apache.accumulo.core.security.ColumnVisibility;
import org.apache.hadoop.io.Text;
import org.calrissian.accumulorecipes.common.domain.StoreEntry;
import org.calrissian.accumulorecipes.lastn.LastNStore;
import org.calrissian.accumulorecipes.lastn.iterator.EntryIterator;
import org.calrissian.accumulorecipes.lastn.iterator.IndexEntryFilteringIterator;
import org.calrissian.accumulorecipes.lastn.support.LastNIterator;
import org.calrissian.commons.domain.Tuple;
import org.calrissian.mango.collect.CloseableIterator;
import org.calrissian.mango.types.TypeContext;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;

import static org.calrissian.accumulorecipes.lastn.support.Constants.DELIM;
import static org.calrissian.accumulorecipes.lastn.support.Constants.DELIM_END;

public class AccumuloLastNStore implements LastNStore {

    protected static final IteratorSetting EVENT_FILTER_SETTING =
            new IteratorSetting(40, "eventFilter", IndexEntryFilteringIterator.class);

    protected final Connector connector;
    protected BatchWriter batchWriter = null;

    protected String tableName = "lastN";

    protected Long maxMemory = 100000L;
    protected Integer numThreads = 3;
    protected Long maxLatency = 10000L;

    protected int maxVersions = 100;

    protected final TypeContext typeContext = TypeContext.getInstance();

    public AccumuloLastNStore(Connector connector) {
        this.connector = connector;
        init();
    }

    public AccumuloLastNStore(Connector connector, int maxVersions) {
        this.connector = connector;
        this.maxVersions = maxVersions;
        init();
    }

    protected void init() {
        try {
            this.batchWriter = connector.createBatchWriter(tableName, maxMemory, maxLatency, numThreads);
            initTable(maxVersions);
        }

        catch(Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void initTable(int maxVersions) throws AccumuloException, AccumuloSecurityException, TableExistsException {
        TableOperations tops = connector.tableOperations();
        if (!tops.exists(tableName)) {
            tops.create(tableName);

            try {

                Collection<IteratorUtil.IteratorScope> scopes = new ArrayList<IteratorUtil.IteratorScope>();
                scopes.add(IteratorUtil.IteratorScope.majc);
                scopes.add(IteratorUtil.IteratorScope.minc);

                EnumSet<IteratorUtil.IteratorScope> scope = EnumSet.copyOf(scopes);

                tops.attachIterator(tableName, EVENT_FILTER_SETTING, scope);

                tops.setProperty(tableName, "table.iterator.majc.vers.opt.maxVersions", Integer.toString(maxVersions));
                tops.setProperty(tableName, "table.iterator.minc.vers.opt.maxVersions", Integer.toString(maxVersions));
                tops.setProperty(tableName, "table.iterator.scan.vers.opt.maxVersions", Integer.toString(maxVersions));

            } catch (TableNotFoundException e) {
                e.printStackTrace();
            }
        }
    }


    @Override
    public void put(String index, StoreEntry entry) {

        // first put the main index pointing to the contextId (The column family is prefixed with the DELIM to guarantee it shows up first
        Mutation indexMutation = new Mutation(index);
        indexMutation.put(DELIM + "INDEX", "", new ColumnVisibility(), entry.getTimestamp(), new Value(entry.getId().getBytes()));

        String fam = null, qual = null;
        for (Tuple tuple : entry.getTuples()) {
            fam = String.format("%s%s", DELIM_END, entry.getId());
            Object value = tuple.getValue();
            try {
                String serialize = typeContext.normalize(value);
                String aliasForType = typeContext.getAliasForType(value);
                qual = String.format("%s%s%s%s%s", tuple.getKey(), DELIM, serialize, DELIM, aliasForType);
                indexMutation.put(fam, qual, new ColumnVisibility(tuple.getVisibility()), entry.getTimestamp(),
                        new Value("".getBytes()));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        try {
            batchWriter.addMutation(indexMutation);
        } catch (MutationsRejectedException ex) {
            throw new RuntimeException("There was an error writing the mutation for [index=" + index + ",entryId=" + entry.getId() + "]", ex);
        }
    }

    @Override
    public CloseableIterator<StoreEntry> get(String index, Authorizations auths) {

        try {
            Scanner scanner = connector.createScanner(tableName, auths);

            IteratorSetting iteratorSetting = new IteratorSetting(16, "eventIterator", EntryIterator.class);
            scanner.addScanIterator(iteratorSetting);

            scanner.addScanIterator(EVENT_FILTER_SETTING);

            scanner.setRange(new Range(index));
            scanner.fetchColumnFamily(new Text(DELIM + "INDEX"));

            return new LastNIterator(scanner);

        } catch (TableNotFoundException e) {
            e.printStackTrace();  //To change body of catch statement use File | Settings | File Templates.
        }
        return null;
    }

    public String getTableName() {
        return tableName;
    }

    public void setTableName(String tableName) {
        this.tableName = tableName;
    }

    public Long getMaxMemory() {
        return maxMemory;
    }

    public void setMaxMemory(Long maxMemory) {
        this.maxMemory = maxMemory;
    }

    public Integer getNumThreads() {
        return numThreads;
    }

    public void setNumThreads(Integer numThreads) {
        this.numThreads = numThreads;
    }

    public Long getMaxLatency() {
        return maxLatency;
    }

    public void setMaxLatency(Long maxLatency) {
        this.maxLatency = maxLatency;
    }
}


