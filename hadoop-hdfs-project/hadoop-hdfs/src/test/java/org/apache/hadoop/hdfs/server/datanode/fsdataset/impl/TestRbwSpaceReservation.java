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

package org.apache.hadoop.hdfs.server.datanode.fsdataset.impl;

import com.google.common.base.Supplier;
import org.apache.commons.io.IOUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import static org.apache.hadoop.hdfs.DFSConfigKeys.*;
import static org.hamcrest.core.Is.is;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hdfs.*;
import org.apache.hadoop.hdfs.protocol.Block;
import org.apache.hadoop.hdfs.server.datanode.DataNode;
import org.apache.hadoop.hdfs.server.datanode.DataNodeFaultInjector;
import org.apache.hadoop.hdfs.server.datanode.fsdataset.FsDatasetSpi;
import org.apache.hadoop.hdfs.server.datanode.fsdataset.FsVolumeSpi;
import org.apache.hadoop.hdfs.server.datanode.fsdataset.FsVolumeReference;
import org.apache.hadoop.ipc.RemoteException;
import org.apache.hadoop.test.GenericTestUtils;
import org.apache.hadoop.util.Daemon;
import org.apache.log4j.Level;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.mockito.Mockito;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeoutException;

/**
 * Ensure that the DN reserves disk space equivalent to a full block for
 * replica being written (RBW).
 */
public class TestRbwSpaceReservation {
  static final Log LOG = LogFactory.getLog(TestRbwSpaceReservation.class);

  private static final int DU_REFRESH_INTERVAL_MSEC = 500;
  private static final int STORAGES_PER_DATANODE = 1;
  private static final int BLOCK_SIZE = 1024 * 1024;
  private static final int SMALL_BLOCK_SIZE = 1024;

  protected MiniDFSCluster cluster;
  private Configuration conf;
  private DistributedFileSystem fs = null;
  private DFSClient client = null;
  FsVolumeReference singletonVolumeRef = null;
  FsVolumeImpl singletonVolume = null;
  private DataNodeFaultInjector old = null;
  private static Random rand = new Random();

  private void initConfig(int blockSize) {
    conf = new HdfsConfiguration();

    // Refresh disk usage information frequently.
    conf.setInt(FS_DU_INTERVAL_KEY, DU_REFRESH_INTERVAL_MSEC);
    conf.setLong(DFS_BLOCK_SIZE_KEY, blockSize);

    // Disable the scanner
    conf.setInt(DFS_DATANODE_SCAN_PERIOD_HOURS_KEY, -1);
  }

  static {
    GenericTestUtils.setLogLevel(FsDatasetImpl.LOG, Level.ALL);
    GenericTestUtils.setLogLevel(DataNode.LOG, Level.ALL);
  }

  /**
   *
   * @param blockSize
   * @param perVolumeCapacity limit the capacity of each volume to the given
   *                          value. If negative, then don't limit.
   * @throws IOException
   */
  private void startCluster(int blockSize, int numDatanodes, long perVolumeCapacity) throws IOException {
    initConfig(blockSize);

    cluster = new MiniDFSCluster
        .Builder(conf)
        .storagesPerDatanode(STORAGES_PER_DATANODE)
        .numDataNodes(numDatanodes)
        .build();
    fs = cluster.getFileSystem();
    client = fs.getClient();
    cluster.waitActive();

    if (perVolumeCapacity >= 0) {
      try (FsDatasetSpi.FsVolumeReferences volumes =
          cluster.getDataNodes().get(0).getFSDataset().getFsVolumeReferences()) {
        singletonVolumeRef = volumes.get(0).obtainReference();
      }
      singletonVolume = ((FsVolumeImpl) singletonVolumeRef.getVolume());
      singletonVolume.setCapacityForTesting(perVolumeCapacity);
    }
  }

  @After
  public void shutdownCluster() throws IOException {
    if (singletonVolumeRef != null) {
      singletonVolumeRef.close();
      singletonVolumeRef = null;
    }

    if (client != null) {
      client.close();
      client = null;
    }

    if (fs != null) {
      fs.close();
      fs = null;
    }

    if (cluster != null) {
      cluster.shutdown();
      cluster = null;
    }
  }

  private void createFileAndTestSpaceReservation(
      final String fileNamePrefix, final int fileBlockSize)
      throws IOException, InterruptedException {
    // Enough for 1 block + meta files + some delta.
    final long configuredCapacity = fileBlockSize * 2 - 1;
    startCluster(BLOCK_SIZE, 1, configuredCapacity);
    FSDataOutputStream out = null;
    Path path = new Path("/" + fileNamePrefix + ".dat");

    try {
      out = fs.create(path, false, 4096, (short) 1, fileBlockSize);

      byte[] buffer = new byte[rand.nextInt(fileBlockSize / 4)];
      out.write(buffer);
      out.hsync();
      int bytesWritten = buffer.length;

      // Check that space was reserved for a full block minus the bytesWritten.
      assertThat(singletonVolume.getReservedForRbw(),
                 is((long) fileBlockSize - bytesWritten));
      out.close();
      out = null;

      // Check that the reserved space has been released since we closed the
      // file.
      assertThat(singletonVolume.getReservedForRbw(), is(0L));

      // Reopen the file for appends and write 1 more byte.
      out = fs.append(path);
      out.write(buffer);
      out.hsync();
      bytesWritten += buffer.length;

      // Check that space was again reserved for a full block minus the
      // bytesWritten so far.
      assertThat(singletonVolume.getReservedForRbw(),
                 is((long) fileBlockSize - bytesWritten));

      // Write once again and again verify the available space. This ensures
      // that the reserved space is progressively adjusted to account for bytes
      // written to disk.
      out.write(buffer);
      out.hsync();
      bytesWritten += buffer.length;
      assertThat(singletonVolume.getReservedForRbw(),
                 is((long) fileBlockSize - bytesWritten));
    } finally {
      if (out != null) {
        out.close();
      }
    }
  }

  @Test (timeout=300000)
  public void testWithDefaultBlockSize()
      throws IOException, InterruptedException {
    createFileAndTestSpaceReservation(GenericTestUtils.getMethodName(), BLOCK_SIZE);
  }

  @Test (timeout=300000)
  public void testWithNonDefaultBlockSize()
      throws IOException, InterruptedException {
    // Same test as previous one, but with a non-default block size.
    createFileAndTestSpaceReservation(GenericTestUtils.getMethodName(), BLOCK_SIZE * 2);
  }

  @Rule
  public ExpectedException thrown = ExpectedException.none();

  @Test (timeout=300000)
  public void testWithLimitedSpace() throws IOException {
    // Cluster with just enough space for a full block + meta.
    startCluster(BLOCK_SIZE, 1, 2 * BLOCK_SIZE - 1);
    final String methodName = GenericTestUtils.getMethodName();
    Path file1 = new Path("/" + methodName + ".01.dat");
    Path file2 = new Path("/" + methodName + ".02.dat");

    // Create two files.
    FSDataOutputStream os1 = null, os2 = null;

    try {
      os1 = fs.create(file1);
      os2 = fs.create(file2);

      // Write one byte to the first file.
      byte[] data = new byte[1];
      os1.write(data);
      os1.hsync();

      // Try to write one byte to the second file.
      // The block allocation must fail.
      thrown.expect(RemoteException.class);
      os2.write(data);
      os2.hsync();
    } finally {
      if (os1 != null) {
        os1.close();
      }

      // os2.close() will fail as no block was allocated.
    }
  }

  /**
   * Ensure that reserved space is released when the client goes away
   * unexpectedly.
   *
   * The verification is done for each replica in the write pipeline.
   *
   * @throws IOException
   */
  @Test(timeout=300000)
  public void testSpaceReleasedOnUnexpectedEof()
      throws IOException, InterruptedException, TimeoutException {
    final short replication = 3;
    startCluster(BLOCK_SIZE, replication, -1);

    final String methodName = GenericTestUtils.getMethodName();
    final Path file = new Path("/" + methodName + ".01.dat");

    // Write 1 byte to the file and kill the writer.
    FSDataOutputStream os = fs.create(file, replication);
    os.write(new byte[1]);
    os.hsync();
    DFSTestUtil.abortStream((DFSOutputStream) os.getWrappedStream());

    // Ensure all space reserved for the replica was released on each
    // DataNode.
    for (DataNode dn : cluster.getDataNodes()) {
      try (FsDatasetSpi.FsVolumeReferences volumes =
          dn.getFSDataset().getFsVolumeReferences()) {
        final FsVolumeImpl volume = (FsVolumeImpl) volumes.get(0);
        GenericTestUtils.waitFor(new Supplier<Boolean>() {
          @Override
          public Boolean get() {
            return (volume.getReservedForRbw() == 0);
          }
        }, 500, Integer.MAX_VALUE); // Wait until the test times out.
      }
    }
  }

  @SuppressWarnings("unchecked")
  @Test(timeout = 30000)
  public void testRBWFileCreationError() throws Exception {

    final short replication = 1;
    startCluster(BLOCK_SIZE, replication, -1);

    final FsVolumeImpl fsVolumeImpl = (FsVolumeImpl) cluster.getDataNodes()
        .get(0).getFSDataset().getFsVolumeReferences().get(0);
    final String methodName = GenericTestUtils.getMethodName();
    final Path file = new Path("/" + methodName + ".01.dat");

    // Mock BlockPoolSlice so that RBW file creation gives IOExcception
    BlockPoolSlice blockPoolSlice = Mockito.mock(BlockPoolSlice.class);
    Mockito.when(blockPoolSlice.createRbwFile((Block) Mockito.any()))
        .thenThrow(new IOException("Synthetic IO Exception Throgh MOCK"));

    Field field = FsVolumeImpl.class.getDeclaredField("bpSlices");
    field.setAccessible(true);
    Map<String, BlockPoolSlice> bpSlices = (Map<String, BlockPoolSlice>) field
        .get(fsVolumeImpl);
    bpSlices.put(fsVolumeImpl.getBlockPoolList()[0], blockPoolSlice);

    try {
      // Write 1 byte to the file
      FSDataOutputStream os = fs.create(file, replication);
      os.write(new byte[1]);
      os.hsync();
      os.close();
      fail("Expecting IOException file creation failure");
    } catch (IOException e) {
      // Exception can be ignored (expected)
    }

    // Ensure RBW space reserved is released
    assertTrue("Expected ZERO but got " + fsVolumeImpl.getReservedForRbw(),
        fsVolumeImpl.getReservedForRbw() == 0);
  }

  /**
   * Stress test to ensure we are not leaking reserved space.
   * @throws IOException
   * @throws InterruptedException
   */
  @Test (timeout=600000)
  public void stressTest() throws IOException, InterruptedException {
    final int numWriters = 5;
    startCluster(SMALL_BLOCK_SIZE, 1, SMALL_BLOCK_SIZE * numWriters * 10);
    Writer[] writers = new Writer[numWriters];

    // Start a few writers and let them run for a while.
    for (int i = 0; i < numWriters; ++i) {
      writers[i] = new Writer(client, SMALL_BLOCK_SIZE);
      writers[i].start();
    }

    Thread.sleep(60000);

    // Stop the writers.
    for (Writer w : writers) {
      w.stopWriter();
    }
    int filesCreated = 0;
    int numFailures = 0;
    for (Writer w : writers) {
      w.join();
      filesCreated += w.getFilesCreated();
      numFailures += w.getNumFailures();
    }

    LOG.info("Stress test created " + filesCreated +
             " files and hit " + numFailures + " failures");

    // Check no space was leaked.
    assertThat(singletonVolume.getReservedForRbw(), is(0L));
  }

  private static class Writer extends Daemon {
    private volatile boolean keepRunning;
    private final DFSClient localClient;
    private int filesCreated = 0;
    private int numFailures = 0;
    byte[] data;

    Writer(DFSClient client, int blockSize) throws IOException {
      localClient = client;
      keepRunning = true;
      filesCreated = 0;
      numFailures = 0;

      // At least some of the files should span a block boundary.
      data = new byte[blockSize * 2];
    }

    @Override
    public void run() {
      /**
       * Create a file, write up to 3 blocks of data and close the file.
       * Do this in a loop until we are told to stop.
       */
      while (keepRunning) {
        OutputStream os = null;
        try {
          String filename = "/file-" + rand.nextLong();
          os = localClient.create(filename, false);
          os.write(data, 0, rand.nextInt(data.length));
          IOUtils.closeQuietly(os);
          os = null;
          localClient.delete(filename, false);
          Thread.sleep(50);     // Sleep for a bit to avoid killing the system.
          ++filesCreated;
        } catch (IOException ioe) {
          // Just ignore the exception and keep going.
          ++numFailures;
        } catch (InterruptedException ie) {
          return;
        } finally {
          if (os != null) {
            IOUtils.closeQuietly(os);
          }
        }
      }
    }

    public void stopWriter() {
      keepRunning = false;
    }

    public int getFilesCreated() {
      return filesCreated;
    }

    public int getNumFailures() {
      return numFailures;
    }
  }

  @Test(timeout = 30000)
  public void testReservedSpaceForAppend() throws Exception {
    final short replication = 3;
    startCluster(BLOCK_SIZE, replication, -1);
    final String methodName = GenericTestUtils.getMethodName();
    final Path file = new Path("/" + methodName + ".01.dat");

    // Write 1 byte to the file and kill the writer.
    FSDataOutputStream os = fs.create(file, replication);
    os.write(new byte[1024]);
    os.close();

    final Path file2 = new Path("/" + methodName + ".02.dat");

    // Write 1 byte to the file and keep it open.
    FSDataOutputStream os2 = fs.create(file2, replication);
    os2.write(new byte[1]);
    os2.hflush();
    int expectedFile2Reserved = BLOCK_SIZE - 1;
    checkReservedSpace(expectedFile2Reserved);

    // append one byte and verify reservedspace before and after closing
    os = fs.append(file);
    os.write(new byte[1]);
    os.hflush();
    int expectedFile1Reserved = BLOCK_SIZE - 1025;
    checkReservedSpace(expectedFile2Reserved + expectedFile1Reserved);
    os.close();
    checkReservedSpace(expectedFile2Reserved);

    // append one byte and verify reservedspace before and after abort
    os = fs.append(file);
    os.write(new byte[1]);
    os.hflush();
    expectedFile1Reserved--;
    checkReservedSpace(expectedFile2Reserved + expectedFile1Reserved);
    DFSTestUtil.abortStream(((DFSOutputStream) os.getWrappedStream()));
    checkReservedSpace(expectedFile2Reserved);
  }

  @Test(timeout = 30000)
  public void testReservedSpaceForPipelineRecovery()
      throws Exception {
    final short replication = 3;
    startCluster(BLOCK_SIZE, replication, -1);

    final String methodName = GenericTestUtils.getMethodName();
    final Path file = new Path("/" + methodName + ".01.dat");

    old = DataNodeFaultInjector.get();
    // Fault injector to fail connection to mirror first time.
    DataNodeFaultInjector.set(new DataNodeFaultInjector() {
      private int tries = 0;

      @Override
      public void failMirrorConnection() throws IOException {
        if (tries++ == 0) {
          throw new IOException("Failing Mirror for space reservation");
        }
      }
    });
    // Write 1 byte to the file and kill the writer.
    FSDataOutputStream os = fs.create(file, replication);
    os.write(new byte[1]);
    os.close();
    // Ensure all space reserved for the replica was released on each
    // DataNode.
    cluster.triggerBlockReports();
    for (final DataNode dn : cluster.getDataNodes()) {
      try (FsDatasetSpi.FsVolumeReferences volumes =
               dn.getFSDataset().getFsVolumeReferences()) {
        final FsVolumeImpl volume = (FsVolumeImpl) volumes.get(0);
        GenericTestUtils.waitFor(new Supplier<Boolean>() {
          @Override
          public Boolean get() {
            LOG.info("dn " + dn.getDisplayName() + " space : "
                + volume.getReservedForRbw());
            return (volume.getReservedForRbw() == 0);
          }
        }, 100, Integer.MAX_VALUE); // Wait until the test times out.
      }
    }
  }

  private void checkReservedSpace(final long expectedReserved)
      throws TimeoutException, InterruptedException, IOException {
    for (final DataNode dn : cluster.getDataNodes()) {
      try (FsDatasetSpi.FsVolumeReferences volumes = dn.getFSDataset()
          .getFsVolumeReferences()) {
        final FsVolumeImpl volume = (FsVolumeImpl) volumes.get(0);
        GenericTestUtils.waitFor(new Supplier<Boolean>() {
          @Override
          public Boolean get() {
            LOG.info(
                "dn " + dn.getDisplayName() + " space : " + volume
                    .getReservedForRbw() + ", Expected ReservedSpace :"
                    + expectedReserved);
            return (volume.getReservedForRbw() == expectedReserved);
          }
        }, 100, 3000);
      }
    }
  }
}
