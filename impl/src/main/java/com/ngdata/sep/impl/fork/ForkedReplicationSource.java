/*
 * Copyright 2010 The Apache Software Foundation
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.ngdata.sep.impl.fork;

import java.io.EOFException;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.NavigableMap;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hbase.HConstants;
import org.apache.hadoop.hbase.KeyValue;
import org.apache.hadoop.hbase.ServerName;
import org.apache.hadoop.hbase.Stoppable;
import org.apache.hadoop.hbase.client.HConnection;
import org.apache.hadoop.hbase.client.HConnectionManager;
import org.apache.hadoop.hbase.ipc.HRegionInterface;
import org.apache.hadoop.hbase.regionserver.wal.HLog;
import org.apache.hadoop.hbase.regionserver.wal.HLogKey;
import org.apache.hadoop.hbase.regionserver.wal.WALEdit;
import org.apache.hadoop.hbase.replication.ReplicationZookeeper;
import org.apache.hadoop.hbase.replication.regionserver.ReplicationHLogReaderManager;
import org.apache.hadoop.hbase.replication.regionserver.ReplicationSourceInterface;
import org.apache.hadoop.hbase.replication.regionserver.ReplicationSourceManager;
import org.apache.hadoop.hbase.replication.regionserver.ReplicationSourceMetrics;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.hbase.util.Threads;
import org.apache.hadoop.hbase.zookeeper.ZKUtil;
import org.apache.hadoop.hbase.zookeeper.ZooKeeperListener;
import org.apache.hadoop.hbase.zookeeper.ZooKeeperWatcher;
import org.apache.hadoop.ipc.RemoteException;
import org.apache.zookeeper.KeeperException;

/**
 * /** Forked version of org.apache.hadoop.hbase.replication.regionserver.ReplicationSource. Updated to be more
 * responsive to changes in the number of regionservers in peer clusters, as well as logging fewer errors. Once
 * HBASE-7634, HBASE-7325, and HBASE-7122 are in place then this class can be removed.
 * 
 * All SEP-specific changes are marked with "SEP change".
 * 
 * If a new version of HBase is used (and the patches listed above are not available in it), the changes marked with
 * "SEP change" should be applied to the new forked version of the class.
 * 
 * Class that handles the source of a replication stream. Currently does not handle more than 1 slave For each slave
 * cluster it selects a random number of peers using a replication ratio. For example, if replication ration = 0.1 and
 * slave cluster has 100 region servers, 10 will be selected.
 * <p/>
 * A stream is considered down when we cannot contact a region server on the peer cluster for more than 55 seconds by
 * default.
 * <p/>
 * 
 */
public class ForkedReplicationSource extends Thread implements ReplicationSourceInterface {

    private static final Log LOG = LogFactory.getLog(ForkedReplicationSource.class);
    // Queue of logs to process
    private PriorityBlockingQueue<Path> queue;
    // container of entries to replicate
    private HLog.Entry[] entriesArray;
    private HConnection conn;
    // Helper class for zookeeper
    private ReplicationZookeeper zkHelper;
    private Configuration conf;
    // ratio of region servers to chose from a slave cluster
    private float ratio;
    private Random random;
    // should we replicate or not?
    private AtomicBoolean replicating;
    // id of the peer cluster this source replicates to
    private String peerId;
    // The manager of all sources to which we ping back our progress
    private ReplicationSourceManager manager;
    // Should we stop everything?
    private Stoppable stopper;
    // List of chosen sinks (region servers)
    private List<ServerName> currentPeers;
    // How long should we sleep for each retry
    private long sleepForRetries;
    // Max size in bytes of entriesArray
    private long replicationQueueSizeCapacity;
    // Max number of entries in entriesArray
    private int replicationQueueNbCapacity;
    // Our reader for the current log
    private HLog.Reader reader;
    // Last position in the log that we sent to ZooKeeper
    private long lastLoggedPosition = -1;
    // Path of the current log
    private volatile Path currentPath;
    private FileSystem fs;
    // id of this cluster
    private UUID clusterId;
    // id of the other cluster
    private UUID peerClusterId;
    // total number of edits we replicated
    private long totalReplicatedEdits = 0;
    // The znode we currently play with
    private String peerClusterZnode;
    // Indicates if this queue is recovered (and will be deleted when depleted)
    private boolean queueRecovered;
    // List of all the dead region servers that had this queue (if recovered)
    private String[] deadRegionServers;
    // Maximum number of retries before taking bold actions
    private int maxRetriesMultiplier;
    // Socket timeouts require even bolder actions since we don't want to DDOS
    private int socketTimeoutMultiplier;
    // Current number of entries that we need to replicate
    private int currentNbEntries = 0;
    // Current number of operations (Put/Delete) that we need to replicate
    private int currentNbOperations = 0;
    // Current size of data we need to replicate
    private int currentSize = 0;
    // Indicates if this particular source is running
    private volatile boolean running = true;
    // Metrics for this source
    private ReplicationSourceMetrics metrics;
    // Handle on the log reader helper
    private ReplicationHLogReaderManager repLogReader;

    // SEP change - The time when the list of sinks was last read
    private volatile long sinkListReadTimetamp;

    // SEP change - The time when the list of sinks was last changed in ZooKeeper
    private volatile long sinkListUpdateTimestamp;

    /**
     * Instantiation method used by region servers
     * 
     * @param conf configuration to use
     * @param fs file system to use
     * @param manager replication manager to ping to
     * @param stopper the atomic boolean to use to stop the regionserver
     * @param replicating the atomic boolean that starts/stops replication
     * @param peerClusterZnode the name of our znode
     * @throws IOException
     */
    @Override
    public void init(final Configuration conf, final FileSystem fs, final ReplicationSourceManager manager,
            final Stoppable stopper, final AtomicBoolean replicating, final String peerClusterZnode) throws IOException {
        this.stopper = stopper;
        this.conf = conf;
        this.replicationQueueSizeCapacity = this.conf.getLong("replication.source.size.capacity", 1024 * 1024 * 64);
        this.replicationQueueNbCapacity = this.conf.getInt("replication.source.nb.capacity", 25000);
        this.entriesArray = new HLog.Entry[this.replicationQueueNbCapacity];
        for (int i = 0; i < this.replicationQueueNbCapacity; i++) {
            this.entriesArray[i] = new HLog.Entry();
        }
        this.maxRetriesMultiplier = this.conf.getInt("replication.source.maxretriesmultiplier", 10);
        this.socketTimeoutMultiplier = maxRetriesMultiplier * maxRetriesMultiplier;
        this.queue = new PriorityBlockingQueue<Path>(conf.getInt("hbase.regionserver.maxlogs", 32),
                new LogsComparator());
        this.conn = HConnectionManager.getConnection(conf);
        this.zkHelper = manager.getRepZkWrapper();
        this.ratio = this.conf.getFloat("replication.source.ratio", 0.1f);
        this.currentPeers = new ArrayList<ServerName>();
        this.random = new Random();
        this.replicating = replicating;
        this.manager = manager;
        this.sleepForRetries = this.conf.getLong("replication.source.sleepforretries", 1000);
        this.fs = fs;
        this.metrics = new ReplicationSourceMetrics(peerClusterZnode);
        this.repLogReader = new ReplicationHLogReaderManager(this.fs, this.conf);
        try {
            this.clusterId = zkHelper.getUUIDForCluster(zkHelper.getZookeeperWatcher());
        } catch (KeeperException ke) {
            throw new IOException("Could not read cluster id", ke);
        }

        // Finally look if this is a recovered queue
        this.checkIfQueueRecovered(peerClusterZnode);
    }

    /**
     * SEP change - This method is added to allow being more responsive to peer cluster regionserver changes.
     * 
     * @throws IOException
     */
    private void registerPeerClusterListener() throws IOException {
        try {
            ZooKeeperWatcher peerZkWatcher = this.zkHelper.getPeer(peerId).getZkw();
            peerZkWatcher.registerListener(new PeerSinkChangeListener(peerZkWatcher));
        } catch (KeeperException ke) {
            throw new IOException("Could not get peer list of region servers", ke);
        }
    }

    // The passed znode will be either the id of the peer cluster or
    // the handling story of that queue in the form of id-servername-*
    private void checkIfQueueRecovered(String peerClusterZnode) {
        String[] parts = peerClusterZnode.split("-");
        this.queueRecovered = parts.length != 1;
        this.peerId = this.queueRecovered ? parts[0] : peerClusterZnode;
        this.peerClusterZnode = peerClusterZnode;
        this.deadRegionServers = new String[parts.length - 1];
        // Extract all the places where we could find the hlogs
        for (int i = 1; i < parts.length; i++) {
            this.deadRegionServers[i - 1] = parts[i];
        }
    }

    /**
     * Select a number of peers at random using the ratio. Mininum 1.
     */
    private void chooseSinks() {
        this.currentPeers.clear();
        List<ServerName> addresses = this.zkHelper.getSlavesAddresses(peerId);
        Set<ServerName> setOfAddr = new HashSet<ServerName>();
        int nbPeers = (int)(Math.ceil(addresses.size() * ratio));
        LOG.info("Getting " + nbPeers + " rs from peer cluster # " + peerId);
        for (int i = 0; i < nbPeers; i++) {
            ServerName sn;
            // Make sure we get one address that we don't already have
            do {
                sn = addresses.get(this.random.nextInt(addresses.size()));
            } while (setOfAddr.contains(sn));
            LOG.info("Choosing peer " + sn);
            setOfAddr.add(sn);
        }
        this.currentPeers.addAll(setOfAddr);
    }

    @Override
    public void enqueueLog(Path log) {
        this.queue.put(log);
        this.metrics.sizeOfLogQueue.set(queue.size());
    }

    @Override
    public void run() {
        connectToPeers();
        // We were stopped while looping to connect to sinks, just abort
        if (!this.isActive()) {
            return;
        }
        int sleepMultiplier = 1;
        // delay this until we are in an asynchronous thread
        while (this.peerClusterId == null) {
            this.peerClusterId = zkHelper.getPeerUUID(this.peerId);
            if (this.peerClusterId == null) {
                if (sleepForRetries("Cannot contact the peer's zk ensemble", sleepMultiplier)) {
                    sleepMultiplier++;
                }
            }
        }
        // resetting to 1 to reuse later
        sleepMultiplier = 1;

        LOG.info("Replicating " + clusterId + " -> " + peerClusterId);

        // If this is recovered, the queue is already full and the first log
        // normally has a position (unless the RS failed between 2 logs)
        if (this.queueRecovered) {
            try {
                this.repLogReader.setPosition(this.zkHelper.getHLogRepPosition(this.peerClusterZnode,
                        this.queue.peek().getName()));
            } catch (KeeperException e) {
                this.terminate("Couldn't get the position of this recovered queue " + peerClusterZnode, e);
            }
        }
        // Loop until we close down
        while (isActive()) {
            // Sleep until replication is enabled again
            if (!isPeerEnabled()) {
                if (sleepForRetries("Replication is disabled", sleepMultiplier)) {
                    sleepMultiplier++;
                }
                continue;
            }
            Path oldPath = getCurrentPath(); // note that in the current scenario,
                                             // oldPath will be null when a log roll
                                             // happens.
            // Get a new path
            boolean hasCurrentPath = getNextPath();
            if (getCurrentPath() != null && oldPath == null) {
                sleepMultiplier = 1; // reset the sleepMultiplier on a path change
            }
            if (!hasCurrentPath) {
                if (sleepForRetries("No log to process", sleepMultiplier)) {
                    sleepMultiplier++;
                }
                continue;
            }
            boolean currentWALisBeingWrittenTo = false;
            // For WAL files we own (rather than recovered), take a snapshot of whether the
            // current WAL file (this.currentPath) is in use (for writing) NOW!
            // Since the new WAL paths are enqueued only after the prev WAL file
            // is 'closed', presence of an element in the queue means that
            // the previous WAL file was closed, else the file is in use (currentPath)
            // We take the snapshot now so that we are protected against races
            // where a new file gets enqueued while the current file is being processed
            // (and where we just finished reading the current file).
            if (!this.queueRecovered && queue.size() == 0) {
                currentWALisBeingWrittenTo = true;
            }
            // Open a reader on it
            if (!openReader(sleepMultiplier)) {
                // Reset the sleep multiplier, else it'd be reused for the next file
                sleepMultiplier = 1;
                continue;
            }

            // If we got a null reader but didn't continue, then sleep and continue
            if (this.reader == null) {
                if (sleepForRetries("Unable to open a reader", sleepMultiplier)) {
                    sleepMultiplier++;
                }
                continue;
            }

            boolean gotIOE = false;
            currentNbEntries = 0;
            currentSize = 0;
            try {
                if (readAllEntriesToReplicateOrNextFile(currentWALisBeingWrittenTo)) {
                    continue;
                }
            } catch (IOException ioe) {
                LOG.warn(peerClusterZnode + " Got: ", ioe);
                gotIOE = true;
                if (ioe.getCause() instanceof EOFException) {

                    boolean considerDumping = false;
                    if (this.queueRecovered) {
                        try {
                            FileStatus stat = this.fs.getFileStatus(this.currentPath);
                            if (stat.getLen() == 0) {
                                LOG.warn(peerClusterZnode + " Got EOF and the file was empty");
                            }
                            considerDumping = true;
                        } catch (IOException e) {
                            LOG.warn(peerClusterZnode + " Got while getting file size: ", e);
                        }
                    } else if (currentNbEntries != 0) {
                        LOG.warn(peerClusterZnode + " Got EOF while reading, " + "looks like this file is broken? "
                                + currentPath);
                        considerDumping = true;
                        currentNbEntries = 0;
                    }

                    if (considerDumping && sleepMultiplier == this.maxRetriesMultiplier && processEndOfFile()) {
                        continue;
                    }
                }
            } finally {
                try {
                    this.repLogReader.closeReader();
                } catch (IOException e) {
                    gotIOE = true;
                    LOG.warn("Unable to finalize the tailing of a file", e);
                }
            }

            // If we didn't get anything to replicate, or if we hit a IOE,
            // wait a bit and retry.
            // But if we need to stop, don't bother sleeping
            if (this.isActive() && (gotIOE || currentNbEntries == 0)) {
                if (this.lastLoggedPosition != this.repLogReader.getPosition()) {
                    this.manager.logPositionAndCleanOldLogs(this.currentPath, this.peerClusterZnode,
                            this.repLogReader.getPosition(), queueRecovered, currentWALisBeingWrittenTo);
                    this.lastLoggedPosition = this.repLogReader.getPosition();
                }

                // SEP change -- be more responsive on a lightly-loaded cluster. This will not be necessary
                // once HBASE-7325 is available
                if (!gotIOE) {
                    sleepMultiplier = 1;
                }

                if (sleepForRetries("Nothing to replicate", sleepMultiplier)) {
                    sleepMultiplier++;
                }
                continue;
            }
            sleepMultiplier = 1;
            shipEdits(currentWALisBeingWrittenTo);

        }
        if (this.conn != null) {
            try {
                this.conn.close();
            } catch (IOException e) {
                LOG.debug("Attempt to close connection failed", e);
            }
        }
        LOG.debug("Source exiting " + peerId);
    }

    /**
     * Read all the entries from the current log files and retain those that need to be replicated. Else, process the
     * end of the current file.
     * 
     * @param currentWALisBeingWrittenTo is the current WAL being written to
     * @return true if we got nothing and went to the next file, false if we got entries
     * @throws IOException
     */
    protected boolean readAllEntriesToReplicateOrNextFile(boolean currentWALisBeingWrittenTo) throws IOException {
        long seenEntries = 0;
        this.repLogReader.seek();
        HLog.Entry entry = this.repLogReader.readNextAndSetPosition(this.entriesArray, this.currentNbEntries);
        while (entry != null) {
            WALEdit edit = entry.getEdit();
            this.metrics.logEditsReadRate.inc(1);
            seenEntries++;
            // Remove all KVs that should not be replicated
            HLogKey logKey = entry.getKey();
            // don't replicate if the log entries originated in the peer
            if (!logKey.getClusterId().equals(peerClusterId)) {
                removeNonReplicableEdits(edit);
                // Don't replicate catalog entries, if the WALEdit wasn't
                // containing anything to replicate and if we're currently not set to replicate
                if (!(Bytes.equals(logKey.getTablename(), HConstants.ROOT_TABLE_NAME) || Bytes.equals(
                        logKey.getTablename(), HConstants.META_TABLE_NAME))
                        && edit.size() != 0 && replicating.get()) {
                    // Only set the clusterId if is a local key.
                    // This ensures that the originator sets the cluster id
                    // and all replicas retain the initial cluster id.
                    // This is *only* place where a cluster id other than the default is set.
                    if (HConstants.DEFAULT_CLUSTER_ID == logKey.getClusterId()) {
                        logKey.setClusterId(this.clusterId);
                    }
                    currentNbOperations += countDistinctRowKeys(edit);
                    currentNbEntries++;
                    currentSize += entry.getEdit().heapSize();
                } else {
                    this.metrics.logEditsFilteredRate.inc(1);
                }
            }
            // Stop if too many entries or too big
            if (currentSize >= this.replicationQueueSizeCapacity || currentNbEntries >= this.replicationQueueNbCapacity) {
                break;
            }

            try {
                entry = this.repLogReader.readNextAndSetPosition(this.entriesArray, this.currentNbEntries);
            } catch (IOException ie) {
                LOG.debug("Break on IOE: " + ie.getMessage());
                break;
            }
        }
        if (currentWALisBeingWrittenTo) {
            return false;
        }
        // If we didn't get anything and the queue has an object, it means we
        // hit the end of the file for sure
        return seenEntries == 0 && processEndOfFile();
    }

    private void connectToPeers() {
        // Connect to peer cluster first, unless we have to stop
        while (this.isActive() && this.currentPeers.size() == 0) {

            try {
                chooseSinks();
                Thread.sleep(this.sleepForRetries);
            } catch (InterruptedException e) {
                LOG.error("Interrupted while trying to connect to sinks", e);
            }
        }
    }

    /**
     * Poll for the next path
     * 
     * @return true if a path was obtained, false if not
     */
    protected boolean getNextPath() {
        try {
            if (this.currentPath == null) {
                this.currentPath = queue.poll(this.sleepForRetries, TimeUnit.MILLISECONDS);
                this.metrics.sizeOfLogQueue.set(queue.size());
            }
        } catch (InterruptedException e) {
            LOG.warn("Interrupted while reading edits", e);
        }
        return this.currentPath != null;
    }

    /**
     * Open a reader on the current path
     * 
     * @param sleepMultiplier by how many times the default sleeping time is augmented
     * @return true if we should continue with that file, false if we are over with it
     */
    protected boolean openReader(int sleepMultiplier) {
        try {
            try {
                this.reader = repLogReader.openReader(this.currentPath);
            } catch (FileNotFoundException fnfe) {
                if (this.queueRecovered) {
                    // We didn't find the log in the archive directory, look if it still
                    // exists in the dead RS folder (there could be a chain of failures
                    // to look at)
                    LOG.info("NB dead servers : " + deadRegionServers.length);
                    for (int i = this.deadRegionServers.length - 1; i >= 0; i--) {

                        Path deadRsDirectory = new Path(manager.getLogDir().getParent(), this.deadRegionServers[i]);
                        Path[] locs = new Path[] { new Path(deadRsDirectory, currentPath.getName()),
                                new Path(deadRsDirectory.suffix(HLog.SPLITTING_EXT), currentPath.getName()), };
                        for (Path possibleLogLocation : locs) {
                            LOG.info("Possible location " + possibleLogLocation.toUri().toString());
                            if (this.manager.getFs().exists(possibleLogLocation)) {
                                // We found the right new location
                                LOG.info("Log " + this.currentPath + " still exists at " + possibleLogLocation);
                                // Breaking here will make us sleep since reader is null
                                return true;
                            }
                        }
                    }
                    // TODO What happens if the log was missing from every single location?
                    // Although we need to check a couple of times as the log could have
                    // been moved by the master between the checks
                    // It can also happen if a recovered queue wasn't properly cleaned,
                    // such that the znode pointing to a log exists but the log was
                    // deleted a long time ago.
                    // For the moment, we'll throw the IO and processEndOfFile
                    throw new IOException("File from recovered queue is " + "nowhere to be found", fnfe);
                } else {
                    // If the log was archived, continue reading from there
                    Path archivedLogLocation = new Path(manager.getOldLogDir(), currentPath.getName());
                    if (this.manager.getFs().exists(archivedLogLocation)) {
                        currentPath = archivedLogLocation;
                        LOG.info("Log " + this.currentPath + " was moved to " + archivedLogLocation);
                        // Open the log at the new location
                        this.openReader(sleepMultiplier);

                    }
                    // TODO What happens the log is missing in both places?
                }
            }
        } catch (IOException ioe) {
            // SEP change - Don't log anything if nothing has been flushed to the
            // HLog yet -- this is handled in HBASE-7122
            // TODO HBASE-7122 can't be cleanly applied here, so we'll have to deal with the extra stack traces for now
            // if (ioe instanceof EOFException && position == 0L) {
            //     Return true;
            //  }
            // End SEP change

            LOG.warn(peerClusterZnode + " Got: ", ioe);
            this.reader = null;
            // TODO Need a better way to determinate if a file is really gone but
            // TODO without scanning all logs dir
            if (sleepMultiplier == this.maxRetriesMultiplier) {
                LOG.warn("Waited too long for this file, considering dumping");
                // return !processEndOfFile();
            }
        }
        return true;
    }

    /**
     * Do the sleeping logic
     * 
     * @param msg Why we sleep
     * @param sleepMultiplier by how many times the default sleeping time is augmented
     * @return True if <code>sleepMultiplier</code> is &lt; <code>maxRetriesMultiplier</code>
     */
    protected boolean sleepForRetries(String msg, int sleepMultiplier) {
        try {
            LOG.debug(msg + ", sleeping " + sleepForRetries + " times " + sleepMultiplier);
            Thread.sleep(this.sleepForRetries * sleepMultiplier);
        } catch (InterruptedException e) {
            LOG.debug("Interrupted while sleeping between retries");
        }
        return sleepMultiplier < maxRetriesMultiplier;
    }

    /**
     * We only want KVs that are scoped other than local
     * 
     * @param edit The KV to check for replication
     */
    protected void removeNonReplicableEdits(WALEdit edit) {
        NavigableMap<byte[], Integer> scopes = edit.getScopes();
        List<KeyValue> kvs = edit.getKeyValues();
        for (int i = edit.size() - 1; i >= 0; i--) {
            KeyValue kv = kvs.get(i);
            // The scope will be null or empty if
            // there's nothing to replicate in that WALEdit
            if (scopes == null || !scopes.containsKey(kv.getFamily())) {
                kvs.remove(i);
            }
        }
    }

    /**
     * Count the number of different row keys in the given edit because of mini-batching. We assume that there's at
     * least one KV in the WALEdit.
     * 
     * @param edit edit to count row keys from
     * @return number of different row keys
     */
    private int countDistinctRowKeys(WALEdit edit) {
        List<KeyValue> kvs = edit.getKeyValues();
        int distinctRowKeys = 1;
        KeyValue lastKV = kvs.get(0);
        for (int i = 0; i < edit.size(); i++) {
            if (!kvs.get(i).matchingRow(lastKV)) {
                distinctRowKeys++;
            }
        }
        return distinctRowKeys;
    }

    /**
     * Do the shipping logic
     * 
     * @param currentWALisBeingWrittenTo was the current WAL being (seemingly) written to when this method was called
     */
    protected void shipEdits(boolean currentWALisBeingWrittenTo) {
        int sleepMultiplier = 1;
        if (this.currentNbEntries == 0) {
            LOG.warn("Was given 0 edits to ship");
            return;
        }

        // SEP change - if the peer regionserver list has been updated in ZooKeeper more recently than
        // the last time we read it, we re-read the list of available replication peers
        if (sinkListUpdateTimestamp > sinkListReadTimetamp) {
            LOG.info("List of peer regionservers changed in ZooKeeper, re-choosing replication sinks");
            chooseSinks();
        }

        while (this.isActive()) {
            if (!isPeerEnabled()) {
                if (sleepForRetries("Replication is disabled", sleepMultiplier)) {
                    sleepMultiplier++;
                }
                continue;
            }
            try {
                HRegionInterface rrs = getRS();
                rrs.replicateLogEntries(Arrays.copyOf(this.entriesArray, currentNbEntries));
                if (this.lastLoggedPosition != this.repLogReader.getPosition()) {
                    this.manager.logPositionAndCleanOldLogs(this.currentPath, this.peerClusterZnode,
                            this.repLogReader.getPosition(), queueRecovered, currentWALisBeingWrittenTo);
                    this.lastLoggedPosition = this.repLogReader.getPosition();
                }
                this.totalReplicatedEdits += currentNbEntries;
                this.metrics.shippedBatchesRate.inc(1);
                this.metrics.shippedOpsRate.inc(this.currentNbOperations);
                this.metrics.setAgeOfLastShippedOp(this.entriesArray[currentNbEntries - 1].getKey().getWriteTime());
                break;

            } catch (IOException ioe) {
                // Didn't ship anything, but must still age the last time we did
                this.metrics.refreshAgeOfLastShippedOp();
                if (ioe instanceof RemoteException) {
                    ioe = ((RemoteException)ioe).unwrapRemoteException();
                    LOG.warn("Can't replicate because of an error on the remote cluster: ", ioe);
                } else {
                    if (ioe instanceof SocketTimeoutException) {
                        // This exception means we waited for more than 60s and nothing
                        // happened, the cluster is alive and calling it right away
                        // even for a test just makes things worse.
                        sleepForRetries("Encountered a SocketTimeoutException. Since the"
                                + "call to the remote cluster timed out, which is usually "
                                + "caused by a machine failure or a massive slowdown", this.socketTimeoutMultiplier);
                    } else {
                        LOG.warn("Can't replicate because of a local or network error: ", ioe);
                    }
                }

                try {

                    // SEP change - do an initial sleep here, because we're doing retries based on exceptions in the
                    // underlying
                    // SEP message processing, so the "is the host down" check below will rarely matter
                    if (sleepForRetries("Since a replication attempt failed", sleepMultiplier)) {
                        sleepMultiplier++;
                    }

                    boolean down;
                    // Spin while the slave is down and we're not asked to shutdown/close
                    do {
                        down = isSlaveDown();
                        if (down) {
                            // SEP change - re-choose sinks if there has been an update to the list of peer nodes
                            if ((sinkListUpdateTimestamp <= sinkListReadTimetamp)
                                    && sleepForRetries("Since we are unable to replicate", sleepMultiplier)) {
                                sleepMultiplier++;
                            } else {
                                chooseSinks();
                            }
                        }
                    } while (this.isActive() && down);
                } catch (InterruptedException e) {
                    LOG.debug("Interrupted while trying to contact the peer cluster");
                }
            }
        }
    }

    /**
     * check whether the peer is enabled or not
     * 
     * @return true if the peer is enabled, otherwise false
     */
    protected boolean isPeerEnabled() {
        return this.replicating.get() && this.zkHelper.getPeerEnabled(peerId);
    }

    /**
     * If the queue isn't empty, switch to the next one Else if this is a recovered queue, it means we're done! Else
     * we'll just continue to try reading the log file
     * 
     * @return true if we're done with the current file, false if we should continue trying to read from it
     */
    protected boolean processEndOfFile() {
        if (this.queue.size() != 0) {
            this.currentPath = null;
            this.repLogReader.finishCurrentFile();
            this.reader = null;
            return true;
        } else if (this.queueRecovered) {
            this.manager.closeRecoveredQueue(this);
            LOG.info("Finished recovering the queue");
            this.running = false;
            return true;
        }
        return false;
    }

    @Override
    public void startup() {
        String n = Thread.currentThread().getName();
        Thread.UncaughtExceptionHandler handler = new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(final Thread t, final Throwable e) {
                LOG.error("Unexpected exception in ReplicationSource," + toString(), e);
            }
        };
        Threads.setDaemonThreadRunning(this, n + ".replicationSource," + peerClusterZnode, handler);
    }

    @Override
    public void terminate(String reason) {
        terminate(reason, null);
    }

    @Override
    public void terminate(String reason, Exception cause) {
        if (cause == null) {
            LOG.info("Closing source " + this.peerClusterZnode + " because: " + reason);

        } else {
            LOG.error("Closing source " + this.peerClusterZnode + " because an error occurred: " + reason, cause);
        }
        this.running = false;
        // Only wait for the thread to die if it's not us
        if (!Thread.currentThread().equals(this)) {
            Threads.shutdown(this, this.sleepForRetries);
        }
    }

    /**
     * Get a new region server at random from this peer
     * 
     * @return
     * @throws IOException
     */
    private HRegionInterface getRS() throws IOException {
        if (this.currentPeers.size() == 0) {
            throw new IOException(this.peerClusterZnode + " has 0 region servers");
        }
        ServerName address = currentPeers.get(random.nextInt(this.currentPeers.size()));
        return this.conn.getHRegionConnection(address.getHostname(), address.getPort());
    }

    /**
     * Check if the slave is down by trying to establish a connection
     * 
     * @return true if down, false if up
     * @throws InterruptedException
     */
    public boolean isSlaveDown() throws InterruptedException {
        final CountDownLatch latch = new CountDownLatch(1);
        Thread pingThread = new Thread() {
            @Override
            public void run() {
                try {
                    HRegionInterface rrs = getRS();
                    // Dummy call which should fail
                    rrs.getHServerInfo();
                    latch.countDown();
                } catch (IOException ex) {
                    if (ex instanceof RemoteException) {
                        ex = ((RemoteException)ex).unwrapRemoteException();
                    }
                    LOG.info("Slave cluster looks down: " + ex.getMessage());
                }
            }
        };
        pingThread.start();
        // awaits returns true if countDown happened
        boolean down = !latch.await(this.sleepForRetries, TimeUnit.MILLISECONDS);
        pingThread.interrupt();
        return down;
    }

    @Override
    public String getPeerClusterZnode() {
        return this.peerClusterZnode;
    }

    @Override
    public String getPeerClusterId() {
        return this.peerId;
    }

    @Override
    public Path getCurrentPath() {
        return this.currentPath;
    }

    private boolean isActive() {
        return !this.stopper.isStopped() && this.running;
    }

    /**
     * SEP change - this class is added to allow being more responsive to changes in the list of region servers in a
     * peer cluster.
     * 
     * Simply listens for changes to the list of region servers in the peer cluster and notifies the enclosing
     * ReplicationSource when a change occurs.
     */
    class PeerSinkChangeListener extends ZooKeeperListener {

        public PeerSinkChangeListener(ZooKeeperWatcher watcher) {
            super(watcher);
            watchNode();
        }

        private void watchNode() {
            try {
                ZKUtil.listChildrenAndWatchForNewChildren(watcher, watcher.rsZNode);
            } catch (KeeperException ke) {
                // If this happens, we're basically out of luck. The underlying zookeeper is a RecoverableZooKeeper,
                // meaning
                // that this is a non-recoverable error that has been encounterd. In this case, automatic updates to the
                // list
                // of regionservers will no longer happen (although there's probably something bigger going on in
                // ZooKeeper that
                // cause things to stop functioning anyhow
                LOG.error("Error while trying to listen to regionserver list updates", ke);
            }
        }

        @Override
        public synchronized void nodeChildrenChanged(String path) {
            if (path.equals(watcher.rsZNode)) {
                LOG.info("Peer regionserver children changed at " + path);
                sinkListUpdateTimestamp = System.currentTimeMillis();
                watchNode();
            }
        }

    }

    /**
     * Comparator used to compare logs together based on their start time
     */
    public static class LogsComparator implements Comparator<Path> {

        @Override
        public int compare(Path o1, Path o2) {
            return Long.valueOf(getTS(o1)).compareTo(getTS(o2));
        }

        @Override
        public boolean equals(Object o) {
            return true;
        }

        /**
         * Split a path to get the start time For example: 10.20.20.171%3A60020.1277499063250
         * 
         * @param p path to split
         * @return start time
         */
        private long getTS(Path p) {
            String[] parts = p.getName().split("\\.");
            return Long.parseLong(parts[parts.length - 1]);
        }
    }

    @Override
    public String getStats() {
        return "Total replicated edits: " + totalReplicatedEdits + ", currently replicating from: " + this.currentPath
                + " at position: " + lastLoggedPosition;
    }
}
