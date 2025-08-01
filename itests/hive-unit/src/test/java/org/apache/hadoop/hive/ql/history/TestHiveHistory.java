/*
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
 
package org.apache.hadoop.hive.ql.history;


import java.io.UnsupportedEncodingException;
import java.lang.reflect.Proxy;
import java.util.LinkedList;
import java.util.Map;



import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hive.cli.CliSessionState;
import org.apache.hadoop.hive.common.LogUtils;
import org.apache.hadoop.hive.common.LogUtils.LogInitializationException;
import org.apache.hadoop.hive.common.io.SessionStream;
import org.apache.hadoop.hive.conf.HiveConf;
import org.apache.hadoop.hive.conf.HiveConf.ConfVars;
import org.apache.hadoop.hive.conf.HiveConfForTest;
import org.apache.hadoop.hive.metastore.Warehouse;
import org.apache.hadoop.hive.ql.DriverFactory;
import org.apache.hadoop.hive.ql.IDriver;
import org.apache.hadoop.hive.ql.history.HiveHistory.Keys;
import org.apache.hadoop.hive.ql.history.HiveHistory.QueryInfo;
import org.apache.hadoop.hive.ql.history.HiveHistory.TaskInfo;
import org.apache.hadoop.hive.ql.io.IgnoreKeyTextOutputFormat;
import org.apache.hadoop.hive.ql.metadata.Hive;
import org.apache.hadoop.hive.ql.plan.LoadTableDesc.LoadFileType;
import org.apache.hadoop.hive.ql.session.SessionState;
import org.apache.hadoop.hive.ql.tools.LineageInfo;
import org.apache.hadoop.mapred.TextInputFormat;
import org.apache.hadoop.util.ExitUtil;

import static org.junit.Assert.fail;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import org.junit.Before;
import org.junit.Test;

/**
 * TestHiveHistory.
 *
 */
public class TestHiveHistory {

  static HiveConf conf;

  private static String tmpdir = System.getProperty("test.tmp.dir");
  private static Path tmppath = new Path(tmpdir);
  private static Hive db;
  private static FileSystem fs;
  /*
   * intialize the tables
   */

  @Before
  public void setUp() {
    try {
      conf = getHiveConf();
      SessionState.start(conf);

      fs = FileSystem.get(conf);
      if (fs.exists(tmppath) && !fs.getFileStatus(tmppath).isDir()) {
        throw new RuntimeException(tmpdir + " exists but is not a directory");
      }

      if (!fs.exists(tmppath)) {
        if (!fs.mkdirs(tmppath)) {
          throw new RuntimeException("Could not make scratch directory "
              + tmpdir);
        }
      }

      conf.setBoolVar(HiveConf.ConfVars.HIVE_SUPPORT_CONCURRENCY, false);

      // copy the test files into hadoop if required.
      int i = 0;
      Path[] hadoopDataFile = new Path[2];
      String[] testFiles = {"kv1.txt", "kv2.txt"};
      String testFileDir = new Path(conf.get("test.data.files")).toUri().getPath();
      for (String oneFile : testFiles) {
        Path localDataFile = new Path(testFileDir, oneFile);
        hadoopDataFile[i] = new Path(tmppath, oneFile);
        fs.copyFromLocalFile(false, true, localDataFile, hadoopDataFile[i]);
        i++;
      }

      // load the test files into tables
      i = 0;
      db = Hive.get(conf);
      String[] srctables = {"src", "src2"};
      LinkedList<String> cols = new LinkedList<String>();
      cols.add("key");
      cols.add("value");
      for (String src : srctables) {
        db.dropTable(Warehouse.DEFAULT_DATABASE_NAME, src, true, true);
        db.createTable(src, cols, null, TextInputFormat.class,
            IgnoreKeyTextOutputFormat.class);
        db.loadTable(hadoopDataFile[i], src,
          LoadFileType.KEEP_EXISTING, false, false, false, false, null, 0, false, false);
        i++;
      }

    } catch (Throwable e) {
      e.printStackTrace();
      throw new RuntimeException("Encountered throwable");
    }
  }

  /**
   * Check history file output for this query.
   */
  @Test
  public void testSimpleQuery() {
    new LineageInfo();
    try {

      // NOTE: It is critical to do this here so that log4j is reinitialized
      // before any of the other core hive classes are loaded
      try {
        LogUtils.initHiveLog4j();
      } catch (LogInitializationException e) {
      }
      HiveConf hconf = getHiveConf();
      hconf.setBoolVar(ConfVars.HIVE_SESSION_HISTORY_ENABLED, true);
      CliSessionState ss = new CliSessionState(hconf);
      ss.in = System.in;
      try {
        ss.out = new SessionStream(System.out, true, "UTF-8");
        ss.err = new SessionStream(System.err, true, "UTF-8");
      } catch (UnsupportedEncodingException e) {
        ExitUtil.terminate(3);
      }

      SessionState.start(ss);

      String cmd = "select a.key+1 from src a";
      IDriver d = DriverFactory.newDriver(conf);
      d.run(cmd);
      HiveHistoryViewer hv = new HiveHistoryViewer(SessionState.get()
          .getHiveHistory().getHistFileName());
      Map<String, QueryInfo> jobInfoMap = hv.getJobInfoMap();
      Map<String, TaskInfo> taskInfoMap = hv.getTaskInfoMap();
      if (jobInfoMap.size() != 1) {
        fail("jobInfo Map size not 1");
      }

      if (taskInfoMap.size() != 1) {
        fail("jobInfo Map size not 1");
      }

      cmd = (String) jobInfoMap.keySet().toArray()[0];
      QueryInfo ji = jobInfoMap.get(cmd);

      if (!ji.hm.get(Keys.QUERY_NUM_TASKS.name()).equals("1")) {
        fail("Wrong number of tasks");
      }

    } catch (Exception e) {
      e.printStackTrace();
      fail("Failed");
    }
  }

  @Test
  public void testQueryloglocParentDirNotExist() throws Exception {
    String parentTmpDir = tmpdir + "/HIVE2654";
    Path parentDirPath = new Path(parentTmpDir);
    try {
      fs.delete(parentDirPath, true);
    } catch (Exception e) {
    }
    try {
      String actualDir = parentTmpDir + "/test";
      HiveConf conf = getHiveConf();
      conf.set(HiveConf.ConfVars.HIVE_HISTORY_FILE_LOC.toString(), actualDir);
      SessionState ss = new CliSessionState(conf);
      HiveHistory hiveHistory = new HiveHistoryImpl(ss);
      Path actualPath = new Path(actualDir);
      if (!fs.exists(actualPath)) {
        fail("Query location path is not exist :" + actualPath.toString());
      }
    } finally {
      try {
        fs.delete(parentDirPath, true);
      } catch (Exception e) {
      }
    }
  }

  /**
   * Check if HiveHistoryImpl class is returned when hive history is enabled
   * @throws Exception
   */
  @Test
  public void testHiveHistoryConfigEnabled() throws Exception {
      HiveConf conf = getHiveConf();
      conf.setBoolVar(ConfVars.HIVE_SESSION_HISTORY_ENABLED, true);
      SessionState ss = new CliSessionState(conf);
      SessionState.start(ss);
      HiveHistory hHistory = ss.getHiveHistory();
      assertEquals("checking hive history class when history is enabled",
          hHistory.getClass(), HiveHistoryImpl.class);
  }
  /**
   * Check if HiveHistory class is a Proxy class when hive history is disabled
   * @throws Exception
   */
  @Test
  public void testHiveHistoryConfigDisabled() throws Exception {
    HiveConf conf = getHiveConf();
    conf.setBoolVar(ConfVars.HIVE_SESSION_HISTORY_ENABLED, false);
    SessionState ss = new CliSessionState(conf);
    SessionState.start(ss);
    HiveHistory hHistory = ss.getHiveHistory();
    assertTrue("checking hive history class when history is disabled",
        hHistory.getClass() != HiveHistoryImpl.class);
    System.err.println("hHistory.getClass" + hHistory.getClass());
    assertTrue("verifying proxy class is used when history is disabled",
        Proxy.isProxyClass(hHistory.getClass()));

  }

  private HiveConf getHiveConf() {
    HiveConf conf = new HiveConfForTest(getClass());
    // to get consistent number of tasks in assertions
    conf.setVar(ConfVars.HIVE_FETCH_TASK_CONVERSION, "none");
    return conf;
  }
}
