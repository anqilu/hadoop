/**
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
package org.apache.hadoop.hdfs.server.mover;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.commons.logging.impl.Log4JLogger;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hdfs.BlockStoragePolicy;
import org.apache.hadoop.hdfs.DFSConfigKeys;
import org.apache.hadoop.hdfs.DFSTestUtil;
import org.apache.hadoop.hdfs.DFSUtil;
import org.apache.hadoop.hdfs.DistributedFileSystem;
import org.apache.hadoop.hdfs.HdfsConfiguration;
import org.apache.hadoop.hdfs.MiniDFSCluster;
import org.apache.hadoop.hdfs.StorageType;
import org.apache.hadoop.hdfs.protocol.DirectoryListing;
import org.apache.hadoop.hdfs.protocol.HdfsConstants;
import org.apache.hadoop.hdfs.protocol.HdfsFileStatus;
import org.apache.hadoop.hdfs.protocol.HdfsLocatedFileStatus;
import org.apache.hadoop.hdfs.protocol.LocatedBlock;
import org.apache.hadoop.hdfs.server.balancer.Dispatcher;
import org.apache.hadoop.hdfs.server.balancer.ExitStatus;
import org.apache.hadoop.hdfs.server.blockmanagement.BlockPlacementPolicy;
import org.apache.hadoop.hdfs.server.namenode.snapshot.SnapshotTestHelper;
import org.apache.hadoop.io.IOUtils;
import org.apache.log4j.Level;
import org.junit.Assert;
import org.junit.Test;

import com.google.common.base.Preconditions;
import com.google.common.collect.Maps;

/**
 * Test the data migration tool (for Archival Storage)
 */
public class TestStorageMover {
  static final Log LOG = LogFactory.getLog(TestStorageMover.class);
  static {
    ((Log4JLogger)LogFactory.getLog(BlockPlacementPolicy.class)
        ).getLogger().setLevel(Level.ALL);
    ((Log4JLogger)LogFactory.getLog(Dispatcher.class)
        ).getLogger().setLevel(Level.ALL);
  }

  private static final int BLOCK_SIZE = 1024;
  private static final short REPL = 3;
  private static final int NUM_DATANODES = 6;
  private static final Configuration DEFAULT_CONF = new HdfsConfiguration();
  private static final BlockStoragePolicy.Suite DEFAULT_POLICIES;
  private static final BlockStoragePolicy HOT;
  private static final BlockStoragePolicy WARM;
  private static final BlockStoragePolicy COLD;

  static {
    DEFAULT_CONF.setLong(DFSConfigKeys.DFS_BLOCK_SIZE_KEY, BLOCK_SIZE);
    DEFAULT_CONF.setLong(DFSConfigKeys.DFS_HEARTBEAT_INTERVAL_KEY, 1L);
    DEFAULT_CONF.setLong(DFSConfigKeys.DFS_MOVER_MOVEDWINWIDTH_KEY, 2000L);

    DEFAULT_POLICIES = BlockStoragePolicy.readBlockStorageSuite(DEFAULT_CONF);
    HOT = DEFAULT_POLICIES.getPolicy("HOT");
    WARM = DEFAULT_POLICIES.getPolicy("WARM");
    COLD = DEFAULT_POLICIES.getPolicy("COLD");
    Dispatcher.setBlockMoveWaitTime(1000L);
  }

  /**
   * This scheme defines files/directories and their block storage policies. It
   * also defines snapshots.
   */
  static class NamespaceScheme {
    final List<Path> dirs;
    final List<Path> files;
    final long fileSize;
    final Map<Path, List<String>> snapshotMap;
    final Map<Path, BlockStoragePolicy> policyMap;

    NamespaceScheme(List<Path> dirs, List<Path> files, long fileSize, 
                    Map<Path,List<String>> snapshotMap,
                    Map<Path, BlockStoragePolicy> policyMap) {
      this.dirs = dirs == null? Collections.<Path>emptyList(): dirs;
      this.files = files == null? Collections.<Path>emptyList(): files;
      this.fileSize = fileSize;
      this.snapshotMap = snapshotMap == null ?
          Collections.<Path, List<String>>emptyMap() : snapshotMap;
      this.policyMap = policyMap;
    }

    /**
     * Create files/directories/snapshots.
     */
    void prepare(DistributedFileSystem dfs, short repl) throws Exception {
      for (Path d : dirs) {
        dfs.mkdirs(d);
      }
      for (Path file : files) {
        DFSTestUtil.createFile(dfs, file, fileSize, repl, 0L);
      }
      for (Map.Entry<Path, List<String>> entry : snapshotMap.entrySet()) {
        for (String snapshot : entry.getValue()) {
          SnapshotTestHelper.createSnapshot(dfs, entry.getKey(), snapshot);
        }
      }
    }

    /**
     * Set storage policies according to the corresponding scheme.
     */
    void setStoragePolicy(DistributedFileSystem dfs) throws Exception {
      for (Map.Entry<Path, BlockStoragePolicy> entry : policyMap.entrySet()) {
        dfs.setStoragePolicy(entry.getKey(), entry.getValue().getName());
      }
    }
  }

  /**
   * This scheme defines DataNodes and their storage, including storage types
   * and remaining capacities.
   */
  static class ClusterScheme {
    final Configuration conf;
    final int numDataNodes;
    final short repl;
    final StorageType[][] storageTypes;
    final long[][] storageCapacities;

    ClusterScheme() {
      this(DEFAULT_CONF, NUM_DATANODES, REPL,
          genStorageTypes(NUM_DATANODES, 1, 1), null);
    }

    ClusterScheme(Configuration conf, int numDataNodes, short repl,
        StorageType[][] types, long[][] capacities) {
      Preconditions.checkArgument(types == null || types.length == numDataNodes);
      Preconditions.checkArgument(capacities == null || capacities.length ==
          numDataNodes);
      this.conf = conf;
      this.numDataNodes = numDataNodes;
      this.repl = repl;
      this.storageTypes = types;
      this.storageCapacities = capacities;
    }
  }

  class MigrationTest {
    private final ClusterScheme clusterScheme;
    private final NamespaceScheme nsScheme;
    private final Configuration conf;

    private MiniDFSCluster cluster;
    private DistributedFileSystem dfs;
    private final BlockStoragePolicy.Suite policies;

    MigrationTest(ClusterScheme cScheme, NamespaceScheme nsScheme) {
      this.clusterScheme = cScheme;
      this.nsScheme = nsScheme;
      this.conf = clusterScheme.conf;
      this.policies = BlockStoragePolicy.readBlockStorageSuite(conf);
    }

    /**
     * Set up the cluster and start NameNode and DataNodes according to the
     * corresponding scheme.
     */
    void setupCluster() throws Exception {
      cluster = new MiniDFSCluster.Builder(conf).numDataNodes(clusterScheme
          .numDataNodes).storageTypes(clusterScheme.storageTypes)
          .storageCapacities(clusterScheme.storageCapacities).build();
      cluster.waitActive();
      dfs = cluster.getFileSystem();
    }

    private void runBasicTest(boolean shotdown) throws Exception {
      setupCluster();
      try {
        prepareNamespace();
        verify(true);

        setStoragePolicy();
        migrate();
        verify(true);
      } finally {
        if (shotdown) {
          shutdownCluster();
        }
      }
    }

    void shutdownCluster() throws Exception {
      IOUtils.cleanup(null, dfs);
      if (cluster != null) {
        cluster.shutdown();
      }
    }

    /**
     * Create files/directories and set their storage policies according to the
     * corresponding scheme.
     */
    void prepareNamespace() throws Exception {
      nsScheme.prepare(dfs, clusterScheme.repl);
    }

    void setStoragePolicy() throws Exception {
      nsScheme.setStoragePolicy(dfs);
    }

    /**
     * Run the migration tool.
     */
    void migrate(String... args) throws Exception {
      runMover();
      Thread.sleep(5000); // let the NN finish deletion
    }

    /**
     * Verify block locations after running the migration tool.
     */
    void verify(boolean verifyAll) throws Exception {
      if (verifyAll) {
        verifyNamespace();
      } else {
        // TODO verify according to the given path list

      }
    }

    private void runMover() throws Exception {
      Collection<URI> namenodes = DFSUtil.getNsServiceRpcUris(conf);
      Map<URI, List<Path>> nnMap = Maps.newHashMap();
      for (URI nn : namenodes) {
        nnMap.put(nn, null);
      }
      int result = Mover.run(nnMap, conf);
      Assert.assertEquals(ExitStatus.SUCCESS.getExitCode(), result);
    }

    private void verifyNamespace() throws Exception {
      HdfsFileStatus status = dfs.getClient().getFileInfo("/");
      verifyRecursively(null, status);
    }

    private void verifyRecursively(final Path parent,
        final HdfsFileStatus status) throws Exception {
      if (status.isDir()) {
        Path fullPath = parent == null ?
            new Path("/") : status.getFullPath(parent);
        DirectoryListing children = dfs.getClient().listPaths(
            fullPath.toString(), HdfsFileStatus.EMPTY_NAME, true);
        for (HdfsFileStatus child : children.getPartialListing()) {
          verifyRecursively(fullPath, child);
        }
      } else if (!status.isSymlink()) { // is file
        verifyFile(parent, status, null);
      }
    }

    void verifyFile(final Path file, final Byte expectedPolicyId)
        throws Exception {
      final Path parent = file.getParent();
      DirectoryListing children = dfs.getClient().listPaths(
          parent.toString(), HdfsFileStatus.EMPTY_NAME, true);
      for (HdfsFileStatus child : children.getPartialListing()) {
        if (child.getLocalName().equals(file.getName())) {
          verifyFile(parent,  child, expectedPolicyId);
          return;
        }
      }
      Assert.fail("File " + file + " not found.");
    }

    private void verifyFile(final Path parent, final HdfsFileStatus status,
        final Byte expectedPolicyId) throws Exception {
      HdfsLocatedFileStatus fileStatus = (HdfsLocatedFileStatus) status;
      byte policyId = fileStatus.getStoragePolicy();
      BlockStoragePolicy policy = policies.getPolicy(policyId);
      if (expectedPolicyId != null) {
        Assert.assertEquals((byte)expectedPolicyId, policy.getId());
      }
      final List<StorageType> types = policy.chooseStorageTypes(
          status.getReplication());
      for(LocatedBlock lb : fileStatus.getBlockLocations().getLocatedBlocks()) {
        final Mover.StorageTypeDiff diff = new Mover.StorageTypeDiff(types,
            lb.getStorageTypes());
        Assert.assertTrue(fileStatus.getFullName(parent.toString())
            + " with policy " + policy + " has non-empty overlap: " + diff,
            diff.removeOverlap());
      }
    }
    
    Replication getReplication(Path file) throws IOException {
      return getOrVerifyReplication(file, null);
    }

    Replication verifyReplication(Path file, int expectedDiskCount,
        int expectedArchiveCount) throws IOException {
      final Replication r = new Replication();
      r.disk = expectedDiskCount;
      r.archive = expectedArchiveCount;
      return getOrVerifyReplication(file, r);
    }

    private Replication getOrVerifyReplication(Path file, Replication expected)
        throws IOException {
      final List<LocatedBlock> lbs = dfs.getClient().getLocatedBlocks(
          file.toString(), 0).getLocatedBlocks();
      Assert.assertEquals(1, lbs.size());

      LocatedBlock lb = lbs.get(0);
      StringBuilder types = new StringBuilder(); 
      final Replication r = new Replication();
      for(StorageType t : lb.getStorageTypes()) {
        types.append(t).append(", ");
        if (t == StorageType.DISK) {
          r.disk++;
        } else if (t == StorageType.ARCHIVE) {
          r.archive++;
        } else {
          Assert.fail("Unexpected storage type " + t);
        }
      }

      if (expected != null) {
        final String s = "file = " + file + "\n  types = [" + types + "]";
        Assert.assertEquals(s, expected, r);
      }
      return r;
    }
  }

  static class Replication {
    int disk;
    int archive;
    
    @Override
    public int hashCode() {
      return disk ^ archive;
    }

    @Override
    public boolean equals(Object obj) {
      if (obj == this) {
        return true;
      } else if (obj == null || !(obj instanceof Replication)) {
        return false;
      }
      final Replication that = (Replication)obj;
      return this.disk == that.disk && this.archive == that.archive;
    }
    
    @Override
    public String toString() {
      return "[disk=" + disk + ", archive=" + archive + "]";
    }
  }
  private static StorageType[][] genStorageTypes(int numDataNodes) {
    return genStorageTypes(numDataNodes, 0, 0);
  }

  private static StorageType[][] genStorageTypes(int numDataNodes,
      int numAllDisk, int numAllArchive) {
    StorageType[][] types = new StorageType[numDataNodes][];
    int i = 0;
    for (; i < numAllDisk; i++) {
      types[i] = new StorageType[]{StorageType.DISK, StorageType.DISK};
    }
    for (; i < numAllDisk + numAllArchive; i++) {
      types[i] = new StorageType[]{StorageType.ARCHIVE, StorageType.ARCHIVE};
    }
    for (; i < types.length; i++) {
      types[i] = new StorageType[]{StorageType.DISK, StorageType.ARCHIVE};
    }
    return types;
  }
  
  private static long[][] genCapacities(int nDatanodes, int numAllDisk,
      int numAllArchive, long diskCapacity, long archiveCapacity) {
    final long[][] capacities = new long[nDatanodes][];
    int i = 0;
    for (; i < numAllDisk; i++) {
      capacities[i] = new long[]{diskCapacity, diskCapacity};
    }
    for (; i < numAllDisk + numAllArchive; i++) {
      capacities[i] = new long[]{archiveCapacity, archiveCapacity};
    }
    for(; i < capacities.length; i++) {
      capacities[i] = new long[]{diskCapacity, archiveCapacity};
    }
    return capacities;
  }

  /**
   * A normal case for Mover: move a file into archival storage
   */
  @Test
  public void testMigrateFileToArchival() throws Exception {
    final Path foo = new Path("/foo");
    Map<Path, BlockStoragePolicy> policyMap = Maps.newHashMap();
    policyMap.put(foo, COLD);
    NamespaceScheme nsScheme = new NamespaceScheme(null, Arrays.asList(foo),
        2*BLOCK_SIZE, null, policyMap);
    ClusterScheme clusterScheme = new ClusterScheme(DEFAULT_CONF,
        NUM_DATANODES, REPL, genStorageTypes(NUM_DATANODES), null);
    new MigrationTest(clusterScheme, nsScheme).runBasicTest(true);
  }

  private static class PathPolicyMap {
    final Map<Path, BlockStoragePolicy> map = Maps.newHashMap();
    final Path hot = new Path("/hot");
    final Path warm = new Path("/warm");
    final Path cold = new Path("/cold");
    final List<Path> files;

    PathPolicyMap(int filesPerDir){
      map.put(hot, HOT);
      map.put(warm, WARM);
      map.put(cold, COLD);
      files = new ArrayList<Path>();
      for(Path dir : map.keySet()) {
        for(int i = 0; i < filesPerDir; i++) {
          files.add(new Path(dir, "file" + i));
        }
      }
    }
    
    NamespaceScheme newNamespaceScheme() {
      return new NamespaceScheme(Arrays.asList(hot, warm, cold),
          files, BLOCK_SIZE/2, null, map);
    }
    
    /** 
     * Move hot files to warm and cold, warm files to hot and cold,
     * and cold files to hot and warm.
     */
    void moveAround(DistributedFileSystem dfs) throws Exception {
      for(Path srcDir : map.keySet()) {
        int i = 0;
        for(Path dstDir : map.keySet()) {
          if (!srcDir.equals(dstDir)) {
            final Path src = new Path(srcDir, "file" + i++);
            final Path dst = new Path(dstDir, srcDir.getName() + "2" + dstDir.getName());
            LOG.info("rename " + src + " to " + dst);
            dfs.rename(src, dst);
          }
        }
      }
    }
  }

  /**
   * Test directories with Hot, Warm and Cold polices.
   */
  @Test
  public void testHotWarmColdDirs() throws Exception {
    PathPolicyMap pathPolicyMap = new PathPolicyMap(3);
    NamespaceScheme nsScheme = pathPolicyMap.newNamespaceScheme();
    ClusterScheme clusterScheme = new ClusterScheme();
    MigrationTest test = new MigrationTest(clusterScheme, nsScheme);

    test.runBasicTest(false);

    pathPolicyMap.moveAround(test.dfs);
    test.migrate();
    test.verify(true);
    test.shutdownCluster();
  }

  /**
   * Test DISK is running out of spaces.
   */
  @Test
  public void testNoSpaceDisk() throws Exception {
    final PathPolicyMap pathPolicyMap = new PathPolicyMap(0);
    final NamespaceScheme nsScheme = pathPolicyMap.newNamespaceScheme();

    final long diskCapacity = (10 + HdfsConstants.MIN_BLOCKS_FOR_WRITE)*BLOCK_SIZE;
    final long archiveCapacity = 100*BLOCK_SIZE;
    final long[][] capacities = genCapacities(NUM_DATANODES, 1, 1,
        diskCapacity, archiveCapacity);
    final ClusterScheme clusterScheme = new ClusterScheme(DEFAULT_CONF,
        NUM_DATANODES, REPL, genStorageTypes(NUM_DATANODES, 1, 1), capacities);
    final MigrationTest test = new MigrationTest(clusterScheme, nsScheme);

    test.runBasicTest(false);

    // create hot files with replication 3 until not more spaces.
    final short replication = 3;
    {
      int hotFileCount = 0;
      try {
        for(; ; hotFileCount++) {
          final Path p = new Path(pathPolicyMap.hot, "file" + hotFileCount);
          DFSTestUtil.createFile(test.dfs, p, BLOCK_SIZE, replication, 0L);
        }
      } catch(IOException e) {
        LOG.info("Expected: hotFileCount=" + hotFileCount, e);
      }
      Assert.assertTrue(hotFileCount >= 2);
    }

    // create hot files with replication 1 to use up all remaining spaces.
    {
      int hotFileCount_r1 = 0;
      try {
        for(; ; hotFileCount_r1++) {
          final Path p = new Path(pathPolicyMap.hot, "file_r1_" + hotFileCount_r1);
          DFSTestUtil.createFile(test.dfs, p, BLOCK_SIZE, (short)1, 0L);
        }
      } catch(IOException e) {
        LOG.info("Expected: hotFileCount_r1=" + hotFileCount_r1, e);
      }
    }

    { // test increasing replication.  Since DISK is full,
      // new replicas should be stored in ARCHIVE as a fallback storage.
      final Path file0 = new Path(pathPolicyMap.hot, "file0");
      final Replication r = test.getReplication(file0);
      final short newReplication = (short)5;
      test.dfs.setReplication(file0, newReplication);
      Thread.sleep(10000);
      test.verifyReplication(file0, r.disk, newReplication - r.disk);
    }

    { // test creating a cold file and then increase replication
      final Path p = new Path(pathPolicyMap.cold, "foo");
      DFSTestUtil.createFile(test.dfs, p, BLOCK_SIZE, replication, 0L);
      test.verifyReplication(p, 0, replication);

      final short newReplication = 5;
      test.dfs.setReplication(p, newReplication);
      Thread.sleep(10000);
      test.verifyReplication(p, 0, newReplication);
    }

    { //test move a hot file to warm
      final Path file1 = new Path(pathPolicyMap.hot, "file1");
      test.dfs.rename(file1, pathPolicyMap.warm);
      test.migrate();
      test.verifyFile(new Path(pathPolicyMap.warm, "file1"), WARM.getId());;
    }

    test.shutdownCluster();
  }

  /**
   * Test ARCHIVE is running out of spaces.
   */
  @Test
  public void testNoSpaceArchive() throws Exception {
    final PathPolicyMap pathPolicyMap = new PathPolicyMap(0);
    final NamespaceScheme nsScheme = pathPolicyMap.newNamespaceScheme();

    final long diskCapacity = 100*BLOCK_SIZE;
    final long archiveCapacity = (10 + HdfsConstants.MIN_BLOCKS_FOR_WRITE)*BLOCK_SIZE;
    final long[][] capacities = genCapacities(NUM_DATANODES, 1, 1,
        diskCapacity, archiveCapacity);
    final ClusterScheme clusterScheme = new ClusterScheme(DEFAULT_CONF,
        NUM_DATANODES, REPL, genStorageTypes(NUM_DATANODES, 1, 1), capacities);
    final MigrationTest test = new MigrationTest(clusterScheme, nsScheme);

    test.runBasicTest(false);

    // create cold files with replication 3 until not more spaces.
    final short replication = 3;
    {
      int coldFileCount = 0;
      try {
        for(; ; coldFileCount++) {
          final Path p = new Path(pathPolicyMap.cold, "file" + coldFileCount);
          DFSTestUtil.createFile(test.dfs, p, BLOCK_SIZE, replication, 0L);
        }
      } catch(IOException e) {
        LOG.info("Expected: coldFileCount=" + coldFileCount, e);
      }
      Assert.assertTrue(coldFileCount >= 2);
    }

    // create cold files with replication 1 to use up all remaining spaces.
    {
      int coldFileCount_r1 = 0;
      try {
        for(; ; coldFileCount_r1++) {
          final Path p = new Path(pathPolicyMap.cold, "file_r1_" + coldFileCount_r1);
          DFSTestUtil.createFile(test.dfs, p, BLOCK_SIZE, (short)1, 0L);
        }
      } catch(IOException e) {
        LOG.info("Expected: coldFileCount_r1=" + coldFileCount_r1, e);
      }
    }

    { // test increasing replication but new replicas cannot be created
      // since no more ARCHIVE space.
      final Path file0 = new Path(pathPolicyMap.cold, "file0");
      final Replication r = test.getReplication(file0);
      LOG.info("XXX " + file0 + ": replication=" + r);
      Assert.assertEquals(0, r.disk);

      final short newReplication = (short)5;
      test.dfs.setReplication(file0, newReplication);
      Thread.sleep(10000);

      test.verifyReplication(file0, 0, r.archive);
    }

    { // test creating a hot file
      final Path p = new Path(pathPolicyMap.hot, "foo");
      DFSTestUtil.createFile(test.dfs, p, BLOCK_SIZE, (short)3, 0L);
    }

    { //test move a cold file to warm
      final Path file1 = new Path(pathPolicyMap.hot, "file1");
      test.dfs.rename(file1, pathPolicyMap.warm);
      test.migrate();
      test.verify(true);
    }

    test.shutdownCluster();
  }
}