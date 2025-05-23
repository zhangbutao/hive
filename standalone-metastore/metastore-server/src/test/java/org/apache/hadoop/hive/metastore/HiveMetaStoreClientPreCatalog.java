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

package org.apache.hadoop.hive.metastore;

import static org.apache.hadoop.hive.metastore.HiveMetaStoreClient.callEmbeddedMetastore;
import static org.apache.hadoop.hive.metastore.Warehouse.DEFAULT_DATABASE_NAME;
import static org.apache.hadoop.hive.metastore.utils.MetaStoreUtils.getDefaultCatalog;
import static org.apache.hadoop.hive.metastore.utils.MetaStoreUtils.prependCatalogToDbName;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.security.PrivilegedExceptionAction;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.NoSuchElementException;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.security.auth.login.LoginException;

import com.google.common.base.Preconditions;

import org.apache.commons.lang3.NotImplementedException;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.classification.InterfaceStability;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hive.common.StatsSetupConst;
import org.apache.hadoop.hive.common.TableName;
import org.apache.hadoop.hive.common.ValidTxnList;
import org.apache.hadoop.hive.common.ValidWriteIdList;
import org.apache.hadoop.hive.metastore.api.*;
import org.apache.hadoop.hive.metastore.api.Package;
import org.apache.hadoop.hive.metastore.conf.MetastoreConf;
import org.apache.hadoop.hive.metastore.conf.MetastoreConf.ConfVars;
import org.apache.hadoop.hive.metastore.hooks.URIResolverHook;
import org.apache.hadoop.hive.metastore.partition.spec.PartitionSpecProxy;
import org.apache.hadoop.hive.metastore.security.HadoopThriftAuthBridge;
import org.apache.hadoop.hive.metastore.txn.TxnCommonUtils;
import org.apache.hadoop.hive.metastore.utils.JavaUtils;
import org.apache.hadoop.hive.metastore.utils.MetaStoreUtils;
import org.apache.hadoop.hive.metastore.utils.SecurityUtils;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.hadoop.util.ReflectionUtils;
import org.apache.hadoop.util.StringUtils;
import org.apache.thrift.TApplicationException;
import org.apache.thrift.TConfiguration;
import org.apache.thrift.TException;
import org.apache.thrift.protocol.TBinaryProtocol;
import org.apache.thrift.protocol.TCompactProtocol;
import org.apache.thrift.protocol.TProtocol;
import org.apache.thrift.transport.layered.TFramedTransport;
import org.apache.thrift.transport.TSocket;
import org.apache.thrift.transport.TTransport;
import org.apache.thrift.transport.TTransportException;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.Lists;

/**
 * Hive Metastore Client.
 * The public implementation of IMetaStoreClient. Methods not inherited from IMetaStoreClient
 * are not public and can change. Hence this is marked as unstable.
 * For users who require retry mechanism when the connection between metastore and client is
 * broken, RetryingMetaStoreClient class should be used.
 */
@InterfaceAudience.Public
@InterfaceStability.Evolving
public class HiveMetaStoreClientPreCatalog implements IMetaStoreClient, AutoCloseable {
  /**
   * Capabilities of the current client. If this client talks to a MetaStore server in a manner
   * implying the usage of some expanded features that require client-side support that this client
   * doesn't have (e.g. a getting a table of a new type), it will get back failures when the
   * capability checking is enabled (the default).
   */
  public final static ClientCapabilities VERSION = new ClientCapabilities(
      Lists.newArrayList(ClientCapability.INSERT_ONLY_TABLES));
  // Test capability for tests.
  public final static ClientCapabilities TEST_VERSION = new ClientCapabilities(
      Lists.newArrayList(ClientCapability.INSERT_ONLY_TABLES, ClientCapability.TEST_CAPABILITY));

  ThriftHiveMetastore.Iface client = null;
  private TTransport transport = null;
  private boolean isConnected = false;
  private URI metastoreUris[];
  private final HiveMetaHookLoader hookLoader;
  protected final Configuration conf;  // Keep a copy of HiveConf so if Session conf changes, we may need to get a new HMS client.
  protected boolean fastpath = false;
  private String tokenStrForm;
  private final boolean localMetaStore;
  private final MetaStoreFilterHook filterHook;
  private final URIResolverHook uriResolverHook;
  private final int fileMetadataBatchSize;

  private Map<String, String> currentMetaVars;

  private static final AtomicInteger connCount = new AtomicInteger(0);

  // for thrift connects
  private int retries = 5;
  private long retryDelaySeconds = 0;
  private final ClientCapabilities version;

  static final protected Logger LOG = LoggerFactory.getLogger(HiveMetaStoreClientPreCatalog.class);

  public HiveMetaStoreClientPreCatalog(Configuration conf) throws MetaException {
    this(conf, null, true);
  }

  public HiveMetaStoreClientPreCatalog(Configuration conf, HiveMetaHookLoader hookLoader) throws MetaException {
    this(conf, hookLoader, true);
  }

  public HiveMetaStoreClientPreCatalog(Configuration conf, HiveMetaHookLoader hookLoader, Boolean allowEmbedded)
    throws MetaException {

    this.hookLoader = hookLoader;
    if (conf == null) {
      conf = MetastoreConf.newMetastoreConf();
      this.conf = conf;
    } else {
      this.conf = new Configuration(conf);
    }
    version = MetastoreConf.getBoolVar(conf, ConfVars.HIVE_IN_TEST) ? TEST_VERSION : VERSION;
    filterHook = loadFilterHooks();
    uriResolverHook = loadUriResolverHook();
    fileMetadataBatchSize = MetastoreConf.getIntVar(
        conf, ConfVars.BATCH_RETRIEVE_OBJECTS_MAX);

    String msUri = MetastoreConf.getVar(conf, ConfVars.THRIFT_URIS);
    localMetaStore = MetastoreConf.isEmbeddedMetaStore(msUri);
    if (localMetaStore) {
      if (!allowEmbedded) {
        throw new MetaException("Embedded metastore is not allowed here. Please configure "
            + ConfVars.THRIFT_URIS.toString() + "; it is currently set to [" + msUri + "]");
      }

      client = callEmbeddedMetastore(this.conf);

      isConnected = true;
      snapshotActiveConf();
      return;
    }

    // get the number retries
    retries = MetastoreConf.getIntVar(conf, ConfVars.THRIFT_CONNECTION_RETRIES);
    retryDelaySeconds = MetastoreConf.getTimeVar(conf,
        ConfVars.CLIENT_CONNECT_RETRY_DELAY, TimeUnit.SECONDS);

    // user wants file store based configuration
    if (MetastoreConf.getVar(conf, ConfVars.THRIFT_URIS) != null) {
      resolveUris();
    } else {
      LOG.error("NOT getting uris from conf");
      throw new MetaException("MetaStoreURIs not found in conf file");
    }

    //If HADOOP_PROXY_USER is set in env or property,
    //then need to create metastore client that proxies as that user.
    String HADOOP_PROXY_USER = "HADOOP_PROXY_USER";
    String proxyUser = System.getenv(HADOOP_PROXY_USER);
    if (proxyUser == null) {
      proxyUser = System.getProperty(HADOOP_PROXY_USER);
    }
    //if HADOOP_PROXY_USER is set, create DelegationToken using real user
    if(proxyUser != null) {
      LOG.info(HADOOP_PROXY_USER + " is set. Using delegation "
          + "token for HiveMetaStore connection.");
      try {
        UserGroupInformation.getLoginUser().getRealUser().doAs(
            new PrivilegedExceptionAction<Void>() {
              @Override
              public Void run() throws Exception {
                open();
                return null;
              }
            });
        String delegationTokenPropString = "DelegationTokenForHiveMetaStoreServer";
        String delegationTokenStr = getDelegationToken(proxyUser, proxyUser);
        SecurityUtils.setTokenStr(UserGroupInformation.getCurrentUser(), delegationTokenStr,
            delegationTokenPropString);
        MetastoreConf.setVar(this.conf, ConfVars.TOKEN_SIGNATURE, delegationTokenPropString);
        close();
      } catch (Exception e) {
        LOG.error("Error while setting delegation token for " + proxyUser, e);
        if(e instanceof MetaException) {
          throw (MetaException)e;
        } else {
          throw new MetaException(e.getMessage());
        }
      }
    }
    // finally open the store
    open();
  }

  private void resolveUris() throws MetaException {
    String metastoreUrisString[] =  MetastoreConf.getVar(conf,
            ConfVars.THRIFT_URIS).split(",");

    List<URI> metastoreURIArray = new ArrayList<URI>();
    try {
      for (String s : metastoreUrisString) {
        URI tmpUri = new URI(s);
        if (tmpUri.getScheme() == null) {
          throw new IllegalArgumentException("URI: " + s
                  + " does not have a scheme");
        }
        if (uriResolverHook != null) {
          metastoreURIArray.addAll(uriResolverHook.resolveURI(tmpUri));
        } else {
          metastoreURIArray.add(new URI(
                  tmpUri.getScheme(),
                  tmpUri.getUserInfo(),
                  HadoopThriftAuthBridge.getBridge().getCanonicalHostName(tmpUri.getHost()),
                  tmpUri.getPort(),
                  tmpUri.getPath(),
                  tmpUri.getQuery(),
                  tmpUri.getFragment()
          ));
        }
      }
      metastoreUris = new URI[metastoreURIArray.size()];
      for (int j = 0; j < metastoreURIArray.size(); j++) {
        metastoreUris[j] = metastoreURIArray.get(j);
      }

      if (MetastoreConf.getVar(conf, ConfVars.THRIFT_URI_SELECTION).equalsIgnoreCase("RANDOM")) {
        List<URI> uriList = Arrays.asList(metastoreUris);
        Collections.shuffle(uriList);
        metastoreUris = (URI[]) uriList.toArray();
      }
    } catch (IllegalArgumentException e) {
      throw (e);
    } catch (Exception e) {
      MetaStoreUtils.throwMetaException(e);
    }
  }


  private MetaStoreFilterHook loadFilterHooks() throws IllegalStateException {
    Class<? extends MetaStoreFilterHook> authProviderClass = MetastoreConf.
        getClass(conf, ConfVars.FILTER_HOOK, DefaultMetaStoreFilterHookImpl.class,
            MetaStoreFilterHook.class);
    String msg = "Unable to create instance of " + authProviderClass.getName() + ": ";
    try {
      Constructor<? extends MetaStoreFilterHook> constructor =
          authProviderClass.getConstructor(Configuration.class);
      return constructor.newInstance(conf);
    } catch (NoSuchMethodException | SecurityException | IllegalAccessException | InstantiationException | IllegalArgumentException | InvocationTargetException e) {
      throw new IllegalStateException(msg + e.getMessage(), e);
    }
  }

  //multiple clients may initialize the hook at the same time
  synchronized private URIResolverHook loadUriResolverHook() throws IllegalStateException {

    String uriResolverClassName =
            MetastoreConf.getAsString(conf, ConfVars.URI_RESOLVER);
    if (uriResolverClassName.equals("")) {
      return null;
    } else {
      LOG.info("Loading uri resolver" + uriResolverClassName);
      try {
        Class<?> uriResolverClass = Class.forName(uriResolverClassName, true,
                JavaUtils.getClassLoader());
        return (URIResolverHook) ReflectionUtils.newInstance(uriResolverClass, null);
      } catch (Exception e) {
        LOG.error("Exception loading uri resolver hook" + e);
        return null;
      }
    }
  }

  /**
   * Swaps the first element of the metastoreUris array with a random element from the
   * remainder of the array.
   */
  private void promoteRandomMetaStoreURI() {
    if (metastoreUris.length <= 1) {
      return;
    }
    Random rng = new Random();
    int index = rng.nextInt(metastoreUris.length - 1) + 1;
    URI tmp = metastoreUris[0];
    metastoreUris[0] = metastoreUris[index];
    metastoreUris[index] = tmp;
  }

  @VisibleForTesting
  public TTransport getTTransport() {
    return transport;
  }

  @Override
  public boolean isLocalMetaStore() {
    return localMetaStore;
  }

  @Override
  public boolean isCompatibleWith(Configuration conf) {
    // Make a copy of currentMetaVars, there is a race condition that
	// currentMetaVars might be changed during the execution of the method
    Map<String, String> currentMetaVarsCopy = currentMetaVars;
    if (currentMetaVarsCopy == null) {
      return false; // recreate
    }
    boolean compatible = true;
    for (ConfVars oneVar : MetastoreConf.metaVars) {
      // Since metaVars are all of different types, use string for comparison
      String oldVar = currentMetaVarsCopy.get(oneVar.getVarname());
      String newVar = MetastoreConf.getAsString(conf, oneVar);
      if (oldVar == null ||
          (oneVar.isCaseSensitive() ? !oldVar.equals(newVar) : !oldVar.equalsIgnoreCase(newVar))) {
        LOG.info("Mestastore configuration " + oneVar.toString() +
            " changed from " + oldVar + " to " + newVar);
        compatible = false;
      }
    }
    return compatible;
  }

  @Override
  public void setHiveAddedJars(String addedJars) {
    MetastoreConf.setVar(conf, ConfVars.ADDED_JARS, addedJars);
  }

  @Override
  public void reconnect() throws MetaException {
    if (localMetaStore) {
      // For direct DB connections we don't yet support reestablishing connections.
      throw new MetaException("For direct MetaStore DB connections, we don't support retries" +
          " at the client level.");
    } else {
      close();

      if (uriResolverHook != null) {
        //for dynamic uris, re-lookup if there are new metastore locations
        resolveUris();
      }

      if (MetastoreConf.getVar(conf, ConfVars.THRIFT_URI_SELECTION).equalsIgnoreCase("RANDOM")) {
        // Swap the first element of the metastoreUris[] with a random element from the rest
        // of the array. Rationale being that this method will generally be called when the default
        // connection has died and the default connection is likely to be the first array element.
        promoteRandomMetaStoreURI();
      }
      open();
    }
  }

  /**
   * @param dbname
   * @param tbl_name
   * @param new_tbl
   * @throws InvalidOperationException
   * @throws MetaException
   * @throws TException
   * @see
   *   org.apache.hadoop.hive.metastore.api.ThriftHiveMetastore.Iface#alter_table(
   *   java.lang.String, java.lang.String,
   *   org.apache.hadoop.hive.metastore.api.Table)
   */
  @Override
  public void alter_table(String dbname, String tbl_name, Table new_tbl)
      throws InvalidOperationException, MetaException, TException {
    alter_table_with_environmentContext(dbname, tbl_name, new_tbl, null);
  }

  @Override
  public void alter_table(String defaultDatabaseName, String tblName, Table table,
      boolean cascade) throws TException {
    EnvironmentContext environmentContext = new EnvironmentContext();
    if (cascade) {
      environmentContext.putToProperties(StatsSetupConst.CASCADE, StatsSetupConst.TRUE);
    }
    alter_table_with_environmentContext(defaultDatabaseName, tblName, table, environmentContext);
  }

  @Override
  public void alter_table_with_environmentContext(String dbname, String tbl_name, Table new_tbl,
      EnvironmentContext envContext) throws TException {
    client.alter_table_with_environment_context(dbname, tbl_name, new_tbl, envContext);
  }

  /**
   * @param dbname
   * @param name
   * @param part_vals
   * @param newPart
   * @throws InvalidOperationException
   * @throws MetaException
   * @throws TException
   * @see org.apache.hadoop.hive.metastore.api.ThriftHiveMetastore.Iface#rename_partition(
   *      java.lang.String, java.lang.String, java.util.List, org.apache.hadoop.hive.metastore.api.Partition)
   */
  @Override
  public void renamePartition(final String dbname, final String name, final List<String> part_vals, final Partition newPart)
      throws InvalidOperationException, MetaException, TException {
    client.rename_partition(dbname, name, part_vals, newPart);
  }

  private void open() throws MetaException {
    isConnected = false;
    TTransportException tte = null;
    boolean useSSL = MetastoreConf.getBoolVar(conf, ConfVars.USE_SSL);
    boolean useSasl = MetastoreConf.getBoolVar(conf, ConfVars.USE_THRIFT_SASL);
    boolean useFramedTransport = MetastoreConf.getBoolVar(conf, ConfVars.USE_THRIFT_FRAMED_TRANSPORT);
    boolean useCompactProtocol = MetastoreConf.getBoolVar(conf, ConfVars.USE_THRIFT_COMPACT_PROTOCOL);
    int clientSocketTimeout = (int) MetastoreConf.getTimeVar(conf,
        ConfVars.CLIENT_SOCKET_TIMEOUT, TimeUnit.MILLISECONDS);
    int connectionTimeout = (int) MetastoreConf.getTimeVar(conf,
        ConfVars.CLIENT_CONNECTION_TIMEOUT, TimeUnit.MILLISECONDS);

    for (int attempt = 0; !isConnected && attempt < retries; ++attempt) {
      for (URI store : metastoreUris) {
        LOG.info("Trying to connect to metastore with URI " + store);

        try {
          if (useSSL) {
            try {
              String trustStorePath = MetastoreConf.getVar(conf, ConfVars.SSL_TRUSTSTORE_PATH).trim();
              if (trustStorePath.isEmpty()) {
                throw new IllegalArgumentException(ConfVars.SSL_TRUSTSTORE_PATH.toString()
                    + " Not configured for SSL connection");
              }
              String trustStorePassword =
                  MetastoreConf.getPassword(conf, MetastoreConf.ConfVars.SSL_TRUSTSTORE_PASSWORD);
              String trustStoreType =
                      MetastoreConf.getVar(conf, ConfVars.SSL_TRUSTSTORE_TYPE).trim();
              String trustStoreAlgorithm =
                      MetastoreConf.getVar(conf, ConfVars.SSL_TRUSTMANAGERFACTORY_ALGORITHM).trim();


              // Create an SSL socket and connect
              transport = SecurityUtils.getSSLSocket(store.getHost(), store.getPort(), clientSocketTimeout,
                  connectionTimeout, trustStorePath, trustStorePassword, trustStoreType, trustStoreAlgorithm);
              LOG.info("Opened an SSL connection to metastore, current connections: " + connCount.incrementAndGet());
            } catch(IOException e) {
              throw new IllegalArgumentException(e);
            } catch(TTransportException e) {
              tte = e;
              throw new MetaException(e.toString());
            }
          } else {
            try {
              transport = new TSocket(new TConfiguration(),
                  store.getHost(), store.getPort(), clientSocketTimeout, connectionTimeout);
            } catch (TTransportException e) {
              tte = e;
              throw new MetaException(e.toString());
            }
          }

          if (useSasl) {
            // Wrap thrift connection with SASL for secure connection.
            try {
              HadoopThriftAuthBridge.Client authBridge =
                HadoopThriftAuthBridge.getBridge().createClient();

              // check if we should use delegation tokens to authenticate
              // the call below gets hold of the tokens if they are set up by hadoop
              // this should happen on the map/reduce tasks if the client added the
              // tokens into hadoop's credential store in the front end during job
              // submission.
              String tokenSig = MetastoreConf.getVar(conf, ConfVars.TOKEN_SIGNATURE);
              // tokenSig could be null
              tokenStrForm = SecurityUtils.getTokenStrForm(tokenSig);

              if(tokenStrForm != null) {
                LOG.info("HMSC::open(): Found delegation token. Creating DIGEST-based thrift connection.");
                // authenticate using delegation tokens via the "DIGEST" mechanism
                transport = authBridge.createClientTransport(null, store.getHost(),
                    "DIGEST", tokenStrForm, transport,
                        MetaStoreUtils.getMetaStoreSaslProperties(conf, useSSL));
              } else {
                LOG.info("HMSC::open(): Could not find delegation token. Creating KERBEROS-based thrift connection.");
                String principalConfig =
                    MetastoreConf.getVar(conf, ConfVars.KERBEROS_PRINCIPAL);
                transport = authBridge.createClientTransport(
                    principalConfig, store.getHost(), "KERBEROS", null,
                    transport, MetaStoreUtils.getMetaStoreSaslProperties(conf, useSSL));
              }
            } catch (IOException ioe) {
              LOG.error("Couldn't create client transport", ioe);
              throw new MetaException(ioe.toString());
            }
          } else {
            if (useFramedTransport) {
              try {
                transport = new TFramedTransport(transport);
              } catch (TTransportException e) {
                LOG.error("Couldn't create client transport", e);
                throw new MetaException(e.toString());
              }
            }
          }

          final TProtocol protocol;
          if (useCompactProtocol) {
            protocol = new TCompactProtocol(transport);
          } else {
            protocol = new TBinaryProtocol(transport);
          }
          client = new ThriftHiveMetastore.Client(protocol);
          try {
            if (!transport.isOpen()) {
              transport.open();
              LOG.info("Opened a connection to metastore, current connections: " + connCount.incrementAndGet());
            }
            isConnected = true;
          } catch (TTransportException e) {
            tte = e;
            if (LOG.isDebugEnabled()) {
              LOG.warn("Failed to connect to the MetaStore Server...", e);
            } else {
              // Don't print full exception trace if DEBUG is not on.
              LOG.warn("Failed to connect to the MetaStore Server...");
            }
          }

          if (isConnected && !useSasl && MetastoreConf.getBoolVar(conf, ConfVars.EXECUTE_SET_UGI)){
            // Call set_ugi, only in unsecure mode.
            try {
              UserGroupInformation ugi = SecurityUtils.getUGI();
              client.set_ugi(ugi.getUserName(), Arrays.asList(ugi.getGroupNames()));
            } catch (LoginException e) {
              LOG.warn("Failed to do login. set_ugi() is not successful, " +
                       "Continuing without it.", e);
            } catch (IOException e) {
              LOG.warn("Failed to find ugi of client set_ugi() is not successful, " +
                  "Continuing without it.", e);
            } catch (TException e) {
              LOG.warn("set_ugi() not successful, Likely cause: new client talking to old server. "
                  + "Continuing without it.", e);
            }
          }
        } catch (MetaException e) {
          LOG.error("Unable to connect to metastore with URI " + store
                    + " in attempt " + attempt, e);
        }
        if (isConnected) {
          break;
        }
      }
      // Wait before launching the next round of connection retries.
      if (!isConnected && retryDelaySeconds > 0) {
        try {
          LOG.info("Waiting " + retryDelaySeconds + " seconds before next connection attempt.");
          Thread.sleep(retryDelaySeconds * 1000);
        } catch (InterruptedException ignore) {}
      }
    }

    if (!isConnected) {
      throw new MetaException("Could not connect to meta store using any of the URIs provided." +
        " Most recent failure: " + StringUtils.stringifyException(tte));
    }

    snapshotActiveConf();

    LOG.info("Connected to metastore.");
  }

  private void snapshotActiveConf() {
    currentMetaVars = new HashMap<>(MetastoreConf.metaVars.length);
    for (ConfVars oneVar : MetastoreConf.metaVars) {
      currentMetaVars.put(oneVar.getVarname(), MetastoreConf.getAsString(conf, oneVar));
    }
  }

  @Override
  public String getTokenStrForm() throws IOException {
    return tokenStrForm;
   }

  @Override
  public void close() {
    isConnected = false;
    currentMetaVars = null;
    try {
      if (null != client) {
        client.shutdown();
      }
    } catch (TException e) {
      LOG.debug("Unable to shutdown metastore client. Will try closing transport directly.", e);
    }
    if ((transport != null) && transport.isOpen()) {
      transport.close();
      LOG.info("Closed a connection to metastore, current connections: " + connCount.decrementAndGet());
    }
  }

  @Override
  public void setMetaConf(String key, String value) throws TException {
    client.setMetaConf(key, value);
  }

  @Override
  public String getMetaConf(String key) throws TException {
    return client.getMetaConf(key);
  }

  /**
   * @param new_part
   * @return the added partition
   * @throws InvalidObjectException
   * @throws AlreadyExistsException
   * @throws MetaException
   * @throws TException
   * @see org.apache.hadoop.hive.metastore.api.ThriftHiveMetastore.Iface#add_partition(org.apache.hadoop.hive.metastore.api.Partition)
   */
  @Override
  public Partition add_partition(Partition new_part) throws TException {
    return add_partition(new_part, null);
  }

  public Partition add_partition(Partition new_part, EnvironmentContext envContext)
      throws TException {
    Partition p = client.add_partition_with_environment_context(new_part, envContext);
    return fastpath ? p : deepCopy(p);
  }

  /**
   * @param new_parts
   * @throws InvalidObjectException
   * @throws AlreadyExistsException
   * @throws MetaException
   * @throws TException
   * @see org.apache.hadoop.hive.metastore.api.ThriftHiveMetastore.Iface#add_partitions(List)
   */
  @Override
  public int add_partitions(List<Partition> new_parts) throws TException {
    return client.add_partitions(new_parts);
  }

  @Override
  public List<Partition> add_partitions(
      List<Partition> parts, boolean ifNotExists, boolean needResults) throws TException {
    if (parts.isEmpty()) {
      return needResults ? new ArrayList<>() : null;
    }
    Partition part = parts.get(0);
    AddPartitionsRequest req = new AddPartitionsRequest(part.getDbName(), part.getTableName(), parts, ifNotExists);
    req.setNeedResult(needResults);
    AddPartitionsResult result = client.add_partitions_req(req);
    return needResults ? filterHook.filterPartitions(result.getPartitions()) : null;
  }

  @Override
  public int add_partitions_pspec(PartitionSpecProxy partitionSpec) throws TException {
    return client.add_partitions_pspec(partitionSpec.toPartitionSpec());
  }

  /**
   * @param table_name
   * @param db_name
   * @param part_vals
   * @return the appended partition
   * @throws InvalidObjectException
   * @throws AlreadyExistsException
   * @throws MetaException
   * @throws TException
   * @see org.apache.hadoop.hive.metastore.api.ThriftHiveMetastore.Iface#append_partition(java.lang.String,
   *      java.lang.String, java.util.List)
   */
  @Override
  public Partition appendPartition(String db_name, String table_name,
      List<String> part_vals) throws TException {
    return appendPartition(db_name, table_name, part_vals, null);
  }

  public Partition appendPartition(String db_name, String table_name, List<String> part_vals,
      EnvironmentContext envContext) throws TException {
    Partition p = client.append_partition_with_environment_context(db_name, table_name,
        part_vals, envContext);
    return fastpath ? p : deepCopy(p);
  }

  @Override
  public Partition appendPartition(String dbName, String tableName, String partName)
      throws TException {
    return appendPartition(dbName, tableName, partName, (EnvironmentContext)null);
  }

  public Partition appendPartition(String dbName, String tableName, String partName,
      EnvironmentContext envContext) throws TException {
    Partition p = client.append_partition_by_name_with_environment_context(dbName, tableName,
        partName, envContext);
    return fastpath ? p : deepCopy(p);
  }

  /**
   * Exchange the partition between two tables
   * @param partitionSpecs partitions specs of the parent partition to be exchanged
   * @param destDb the db of the destination table
   * @param destinationTableName the destination table name
   * @return new partition after exchanging
   */
  @Override
  public Partition exchange_partition(Map<String, String> partitionSpecs,
      String sourceDb, String sourceTable, String destDb,
      String destinationTableName) throws TException {
    return client.exchange_partition(partitionSpecs, sourceDb, sourceTable,
        destDb, destinationTableName);
  }

  /**
   * Exchange the partitions between two tables
   * @param partitionSpecs partitions specs of the parent partition to be exchanged
   * @param destDb the db of the destination table
   * @param destinationTableName the destination table name
   * @return new partitions after exchanging
   */
  @Override
  public List<Partition> exchange_partitions(Map<String, String> partitionSpecs,
      String sourceDb, String sourceTable, String destDb,
      String destinationTableName) throws TException {
    return client.exchange_partitions(partitionSpecs, sourceDb, sourceTable,
        destDb, destinationTableName);
  }

  @Override
  public void validatePartitionNameCharacters(List<String> partVals) throws TException {
    client.partition_name_has_valid_characters(partVals, true);
  }

  /**
   * Create a new Database
   * @param connector
   * @throws AlreadyExistsException
   * @throws InvalidObjectException
   * @throws MetaException
   * @throws TException
   * @see org.apache.hadoop.hive.metastore.api.ThriftHiveMetastore.Iface#create_database(Database)
   */
  @Override
  public void createDatabase(Database connector)
      throws AlreadyExistsException, InvalidObjectException, MetaException, TException {
    client.create_database(connector);
  }

  @Override
  public void createDataConnector(DataConnector connector) throws TException {
    CreateDataConnectorRequest connectorReq = new CreateDataConnectorRequest(connector);
    client.create_dataconnector_req(connectorReq);
  }

  /**
   * Dry run that translates table
   *    *
   *    * @param tbl
   *    *          a table object
   *    * @throws HiveException
   */
  @Override
  public Table getTranslateTableDryrun(Table tbl) throws TException {
    CreateTableRequest request = new CreateTableRequest(tbl);
    return client.translate_table_dryrun(request);
  }

  /**
   * @param tbl
   * @throws MetaException
   * @throws NoSuchObjectException
   * @throws TException
   * @see org.apache.hadoop.hive.metastore.api.ThriftHiveMetastore.Iface#create_table(org.apache.hadoop.hive.metastore.api.Table)
   */
  @Override
  public void createTable(Table tbl) throws MetaException, NoSuchObjectException, TException {
    CreateTableRequest request = new CreateTableRequest(tbl);
    createTable(request);
  }

  public void createTable(Table tbl, EnvironmentContext envContext) throws TException {
    CreateTableRequest request = new CreateTableRequest(tbl);
    request.setEnvContext(envContext);
    createTable(request);
  }


  public void createTable(CreateTableRequest request) throws TException {
    Table tbl = request.getTable();
    HiveMetaHook hook = getHook(tbl);
    if (hook != null) {
      hook.preCreateTable(tbl);
    }
    boolean success = false;
    try {
      // Subclasses can override this step (for example, for temporary tables)
      client.create_table_req(request);
      if (hook != null) {
        hook.commitCreateTable(tbl);
      }
      success = true;
    }
    finally {
      if (!success && (hook != null)) {
        try {
          hook.rollbackCreateTable(tbl);
        } catch (Exception e){
          LOG.error("Create rollback failed with", e);
        }
      }
    }
  }

  @Override
  public void createTableWithConstraints(Table tbl,
      List<SQLPrimaryKey> primaryKeys, List<SQLForeignKey> foreignKeys,
      List<SQLUniqueConstraint> uniqueConstraints,
      List<SQLNotNullConstraint> notNullConstraints,
      List<SQLDefaultConstraint> defaultConstraints,
      List<SQLCheckConstraint> checkConstraints)
        throws TException {
    HiveMetaHook hook = getHook(tbl);
    if (hook != null) {
      hook.preCreateTable(tbl);
    }
    boolean success = false;
    try {
      // Subclasses can override this step (for example, for temporary tables)
      client.create_table_with_constraints(tbl, primaryKeys, foreignKeys,
          uniqueConstraints, notNullConstraints, defaultConstraints, checkConstraints);
      if (hook != null) {
        hook.commitCreateTable(tbl);
      }
      success = true;
    } finally {
      if (!success && (hook != null)) {
        hook.rollbackCreateTable(tbl);
      }
    }
  }

  @Override
  public void dropConstraint(String dbName, String tableName, String constraintName) throws TException {
    client.drop_constraint(new DropConstraintRequest(dbName, tableName, constraintName));
  }

  @Override
  public void addPrimaryKey(List<SQLPrimaryKey> primaryKeyCols) throws TException {
    client.add_primary_key(new AddPrimaryKeyRequest(primaryKeyCols));
  }

  @Override
  public void addForeignKey(List<SQLForeignKey> foreignKeyCols) throws TException {
    client.add_foreign_key(new AddForeignKeyRequest(foreignKeyCols));
  }

  @Override
  public void addUniqueConstraint(List<SQLUniqueConstraint> uniqueConstraintCols) throws TException {
    client.add_unique_constraint(new AddUniqueConstraintRequest(uniqueConstraintCols));
  }

  @Override
  public void addNotNullConstraint(List<SQLNotNullConstraint> notNullConstraintCols) throws TException {
    client.add_not_null_constraint(new AddNotNullConstraintRequest(notNullConstraintCols));
  }

  @Override
  public void addDefaultConstraint(List<SQLDefaultConstraint> defaultConstraints) throws TException {
    client.add_default_constraint(new AddDefaultConstraintRequest(defaultConstraints));
  }

  @Override
  public void addCheckConstraint(List<SQLCheckConstraint> checkConstraints) throws TException {
    client.add_check_constraint(new AddCheckConstraintRequest(checkConstraints));
  }

  /**
   * @param type
   * @return true or false
   * @throws AlreadyExistsException
   * @throws InvalidObjectException
   * @throws MetaException
   * @throws TException
   * @see org.apache.hadoop.hive.metastore.api.ThriftHiveMetastore.Iface#create_type(org.apache.hadoop.hive.metastore.api.Type)
   */
  public boolean createType(Type type) throws AlreadyExistsException,
      InvalidObjectException, MetaException, TException {
    return client.create_type(type);
  }

  /**
   * @param name
   * @throws NoSuchObjectException
   * @throws InvalidOperationException
   * @throws MetaException
   * @throws TException
   * @see org.apache.hadoop.hive.metastore.api.ThriftHiveMetastore.Iface#drop_database(java.lang.String, boolean, boolean)
   */
  @Override
  public void dropDatabase(String name)
      throws NoSuchObjectException, InvalidOperationException, MetaException, TException {
    dropDatabase(name, true, false, false);
  }

  @Override
  public void dropDatabase(String name, boolean deleteData, boolean ignoreUnknownDb)
      throws TException {
    dropDatabase(name, deleteData, ignoreUnknownDb, false);
  }

  @Override
  public void dropDatabase(String name, boolean deleteData, boolean ignoreUnknownDb, boolean cascade)
      throws TException {
    try {
      getDatabase(name);
    } catch (NoSuchObjectException e) {
      if (!ignoreUnknownDb) {
        throw e;
      }
      return;
    }

    if (cascade) {
       List<String> tableList = getAllTables(name);
       for (String table : tableList) {
         try {
           // Subclasses can override this step (for example, for temporary tables)
           dropTable(name, table, deleteData, true);
         } catch (UnsupportedOperationException e) {
           // Ignore Index tables, those will be dropped with parent tables
         }
        }
    }
    client.drop_database(name, deleteData, cascade);
  }

  @Override
  public void dropDataConnector(String name, boolean ifNotExists, boolean checkReferences) throws TException {
    DropDataConnectorRequest dropDcReq = new DropDataConnectorRequest(name);
    dropDcReq.setIfNotExists(ifNotExists);
    dropDcReq.setCheckReferences(checkReferences);
    client.drop_dataconnector_req(dropDcReq);
  }

  /**
   * @param tbl_name
   * @param db_name
   * @param part_vals
   * @return true or false
   * @throws NoSuchObjectException
   * @throws MetaException
   * @throws TException
   * @see org.apache.hadoop.hive.metastore.api.ThriftHiveMetastore.Iface#drop_partition(java.lang.String,
   *      java.lang.String, java.util.List, boolean)
   */
  public boolean dropPartition(String db_name, String tbl_name,
      List<String> part_vals) throws NoSuchObjectException, MetaException,
      TException {
    return dropPartition(db_name, tbl_name, part_vals, true, null);
  }

  public boolean dropPartition(String db_name, String tbl_name, List<String> part_vals,
      EnvironmentContext env_context) throws TException {
    return dropPartition(db_name, tbl_name, part_vals, true, env_context);
  }

  @Override
  public boolean dropPartition(String dbName, String tableName, String partName, boolean deleteData)
      throws TException {
    return dropPartition(dbName, tableName, partName, deleteData, null);
  }

  private static EnvironmentContext getEnvironmentContextWithIfPurgeSet() {
    Map<String, String> warehouseOptions = new HashMap<>();
    warehouseOptions.put("ifPurge", "TRUE");
    return new EnvironmentContext(warehouseOptions);
  }

  /*
  public boolean dropPartition(String dbName, String tableName, String partName, boolean deleteData, boolean ifPurge)
      throws NoSuchObjectException, MetaException, TException {

    return dropPartition(dbName, tableName, partName, deleteData,
                         ifPurge? getEnvironmentContextWithIfPurgeSet() : null);
  }
  */

  public boolean dropPartition(String dbName, String tableName, String partName, boolean deleteData,
      EnvironmentContext envContext) throws TException {
    return client.drop_partition_by_name_with_environment_context(dbName, tableName, partName,
        deleteData, envContext);
  }

  /**
   * @param db_name
   * @param tbl_name
   * @param part_vals
   * @param deleteData
   *          delete the underlying data or just delete the table in metadata
   * @return true or false
   * @throws NoSuchObjectException
   * @throws MetaException
   * @throws TException
   * @see org.apache.hadoop.hive.metastore.api.ThriftHiveMetastore.Iface#drop_partition(java.lang.String,
   *      java.lang.String, java.util.List, boolean)
   */
  @Override
  public boolean dropPartition(String db_name, String tbl_name,
      List<String> part_vals, boolean deleteData) throws NoSuchObjectException,
      MetaException, TException {
    return dropPartition(db_name, tbl_name, part_vals, deleteData, null);
  }

  @Override
  public boolean dropPartition(String db_name, String tbl_name,
      List<String> part_vals, PartitionDropOptions options) throws TException {
    return dropPartition(db_name, tbl_name, part_vals, options.deleteData,
                         options.purgeData? getEnvironmentContextWithIfPurgeSet() : null);
  }

  public boolean dropPartition(String db_name, String tbl_name, List<String> part_vals,
      boolean deleteData, EnvironmentContext envContext) throws TException {
    return client.drop_partition_with_environment_context(db_name, tbl_name, part_vals, deleteData,
        envContext);
  }

  @Override
  public List<Partition> dropPartitions(String dbName, String tblName,
                                        List<Pair<Integer, byte[]>> partExprs, PartitionDropOptions options)
      throws TException {
    RequestPartsSpec rps = new RequestPartsSpec();
    List<DropPartitionsExpr> exprs = new ArrayList<>(partExprs.size());
    for (Pair<Integer, byte[]> partExpr : partExprs) {
      DropPartitionsExpr dpe = new DropPartitionsExpr();
      dpe.setExpr(partExpr.getRight());
      dpe.setPartArchiveLevel(partExpr.getLeft());
      exprs.add(dpe);
    }
    rps.setExprs(exprs);
    DropPartitionsRequest req = new DropPartitionsRequest(dbName, tblName, rps);
    req.setDeleteData(options.deleteData);
    req.setNeedResult(options.returnResults);
    req.setIfExists(options.ifExists);
    if (options.purgeData) {
      LOG.info("Dropped partitions will be purged!");
      req.setEnvironmentContext(getEnvironmentContextWithIfPurgeSet());
    }
    return client.drop_partitions_req(req).getPartitions();
  }

  @Override
  public List<Partition> dropPartitions(String dbName, String tblName,
      List<Pair<Integer, byte[]>> partExprs, boolean deleteData,
      boolean ifExists, boolean needResult) throws TException {

    return dropPartitions(dbName, tblName, partExprs,
                          PartitionDropOptions.instance()
                                              .deleteData(deleteData)
                                              .ifExists(ifExists)
                                              .returnResults(needResult));

  }

  @Override
  public List<Partition> dropPartitions(String dbName, String tblName,
      List<Pair<Integer, byte[]>> partExprs, boolean deleteData,
      boolean ifExists) throws TException {
    // By default, we need the results from dropPartitions();
    return dropPartitions(dbName, tblName, partExprs,
                          PartitionDropOptions.instance()
                                              .deleteData(deleteData)
                                              .ifExists(ifExists));
  }

  /**
   * {@inheritDoc}
   * @see #dropTable(String, String, boolean, boolean, EnvironmentContext)
   */
  @Override
  public void dropTable(String dbname, String name, boolean deleteData,
      boolean ignoreUnknownTab) throws TException, UnsupportedOperationException {
    dropTable(dbname, name, deleteData, ignoreUnknownTab, null);
  }

  /**
   * Drop the table and choose whether to save the data in the trash.
   * @param ifPurge completely purge the table (skipping trash) while removing
   *                data from warehouse
   * @see #dropTable(String, String, boolean, boolean, EnvironmentContext)
   */
  @Override
  public void dropTable(String dbname, String name, boolean deleteData,
      boolean ignoreUnknownTab, boolean ifPurge)
      throws TException, UnsupportedOperationException {
    //build new environmentContext with ifPurge;
    EnvironmentContext envContext = null;
    if(ifPurge){
      Map<String, String> warehouseOptions;
      warehouseOptions = new HashMap<>();
      warehouseOptions.put("ifPurge", "TRUE");
      envContext = new EnvironmentContext(warehouseOptions);
    }
    dropTable(dbname, name, deleteData, ignoreUnknownTab, envContext);
  }
  
  @Override
  public void dropTable(Table table, boolean deleteData, boolean ignoreUnknownTab, boolean ifPurge) throws TException {
    dropTable(table.getDbName(), table.getTableName(), deleteData, ignoreUnknownTab, ifPurge);
  }

  /**
   * @see #dropTable(String, String, boolean, boolean, EnvironmentContext)
   */
  @Override
  public void dropTable(String dbname, String name) throws TException {
    dropTable(dbname, name, true, true, null);
  }

  /**
   * Drop the table and choose whether to: delete the underlying table data;
   * throw if the table doesn't exist; save the data in the trash.
   *
   * @param dbname
   * @param name
   * @param deleteData
   *          delete the underlying data or just delete the table in metadata
   * @param ignoreUnknownTab
   *          don't throw if the requested table doesn't exist
   * @param envContext
   *          for communicating with thrift
   * @throws MetaException
   *           could not drop table properly
   * @throws NoSuchObjectException
   *           the table wasn't found
   * @throws TException
   *           a thrift communication error occurred
   * @throws UnsupportedOperationException
   *           dropping an index table is not allowed
   * @see org.apache.hadoop.hive.metastore.api.ThriftHiveMetastore.Iface#drop_table(java.lang.String,
   *      java.lang.String, boolean)
   */
  public void dropTable(String dbname, String name, boolean deleteData,
      boolean ignoreUnknownTab, EnvironmentContext envContext) throws MetaException, TException,
      NoSuchObjectException, UnsupportedOperationException {
    Table tbl;
    try {
      tbl = getTable(dbname, name);
    } catch (NoSuchObjectException e) {
      if (!ignoreUnknownTab) {
        throw e;
      }
      return;
    }
    HiveMetaHook hook = getHook(tbl);
    if (hook != null) {
      hook.preDropTable(tbl);
    }
    boolean success = false;
    try {
      drop_table_with_environment_context(dbname, name, deleteData, envContext);
      if (hook != null) {
        hook.commitDropTable(tbl, deleteData || (envContext != null && "TRUE".equals(envContext.getProperties().get("ifPurge"))));
      }
      success=true;
    } catch (NoSuchObjectException e) {
      if (!ignoreUnknownTab) {
        throw e;
      }
    } finally {
      if (!success && (hook != null)) {
        hook.rollbackDropTable(tbl);
      }
    }
  }

  /**
   * Truncate the table/partitions in the DEFAULT database.
   * @param dbName
   *          The db to which the table to be truncate belongs to
   * @param tableName
   *          The table to truncate
   * @param partNames
   *          List of partitions to truncate. NULL will truncate the whole table/all partitions
   * @throws MetaException
   * @throws TException
   *           Could not truncate table properly.
   */
  @Override
  public void truncateTable(String dbName, String tableName, List<String> partNames) throws MetaException, TException {
    client.truncate_table(dbName, tableName, partNames);
  }

  /**
   * Recycles the files recursively from the input path to the cmroot directory either by copying or moving it.
   *
   * @param request Inputs for path of the data files to be recycled to cmroot and
   *                isPurge flag when set to true files which needs to be recycled are not moved to Trash
   * @return Response which is currently void
   */
  @Override
  public CmRecycleResponse recycleDirToCmPath(CmRecycleRequest request) throws TException {
    return client.cm_recycle(request);
  }

  /**
   * @param type
   * @return true if the type is dropped
   * @throws MetaException
   * @throws TException
   * @see org.apache.hadoop.hive.metastore.api.ThriftHiveMetastore.Iface#drop_type(java.lang.String)
   */
  public boolean dropType(String type) throws MetaException, TException {
    return client.drop_type(type);
  }

  /**
   * @param name
   * @return map of types
   * @throws MetaException
   * @throws TException
   * @see org.apache.hadoop.hive.metastore.api.ThriftHiveMetastore.Iface#get_type_all(java.lang.String)
   */
  public Map<String, Type> getTypeAll(String name) throws MetaException,
      TException {
    Map<String, Type> result = null;
    Map<String, Type> fromClient = client.get_type_all(name);
    if (fromClient != null) {
      result = new LinkedHashMap<>();
      for (String key : fromClient.keySet()) {
        result.put(key, deepCopy(fromClient.get(key)));
      }
    }
    return result;
  }

  /** {@inheritDoc} */
  @Override
  public List<String> getDatabases(String databasePattern)
    throws MetaException {
    try {
      return filterHook.filterDatabases(client.get_databases(databasePattern));
    } catch (Exception e) {
      MetaStoreUtils.throwMetaException(e);
    }
    return null;
  }

  /** {@inheritDoc} */
  @Override
  public List<String> getAllDatabases() throws MetaException {
    try {
      return filterHook.filterDatabases(client.get_all_databases());
    } catch (Exception e) {
      MetaStoreUtils.throwMetaException(e);
    }
    return null;
  }

  /** {@inheritDoc} */
  @Override
  public List<String> getAllDataConnectorNames() throws MetaException {
    try {
      client.get_dataconnectors(); // TODO run thru filterhook
    } catch (Exception e) {
      MetaStoreUtils.throwMetaException(e);
    }
    return null;
  }

  /**
   * @param tbl_name
   * @param db_name
   * @param max_parts
   * @return list of partitions
   * @throws NoSuchObjectException
   * @throws MetaException
   * @throws TException
   */
  @Override
  public List<Partition> listPartitions(String db_name, String tbl_name,
      short max_parts) throws NoSuchObjectException, MetaException, TException {
    List<Partition> parts = client.get_partitions(db_name, tbl_name, max_parts);
    return fastpath ? parts : deepCopyPartitions(filterHook.filterPartitions(parts));
  }

  @Override
  public PartitionSpecProxy listPartitionSpecs(String dbName, String tableName, int maxParts) throws TException {
    return PartitionSpecProxy.Factory.get(filterHook.filterPartitionSpecs(
        client.get_partitions_pspec(dbName, tableName, maxParts)));
  }

  @Override
  public List<Partition> listPartitions(String db_name, String tbl_name,
      List<String> part_vals, short max_parts) throws TException {
    List<Partition> parts = client.get_partitions_ps(db_name, tbl_name, part_vals, max_parts);
    return fastpath ? parts : deepCopyPartitions(filterHook.filterPartitions(parts));
  }

  @Override
  public List<Partition> listPartitionsWithAuthInfo(String db_name,
      String tbl_name, short max_parts, String user_name, List<String> group_names) throws TException {
    List<Partition> parts = client.get_partitions_with_auth(db_name, tbl_name, max_parts,
        user_name, group_names);
    return fastpath ? parts :deepCopyPartitions(filterHook.filterPartitions(parts));
  }

  @Override
  public GetPartitionsPsWithAuthResponse listPartitionsWithAuthInfoRequest(GetPartitionsPsWithAuthRequest req)
      throws TException {
    GetPartitionsPsWithAuthResponse res = client.get_partitions_ps_with_auth_req(req);
    List<Partition> parts = fastpath ? res.getPartitions() :
        deepCopyPartitions(filterHook.filterPartitions(res.getPartitions()));
    res.setPartitions(parts);
    return res;
  }

  @Override
  public List<Partition> listPartitionsWithAuthInfo(String db_name,
      String tbl_name, List<String> part_vals, short max_parts,
      String user_name, List<String> group_names) throws TException {
    List<Partition> parts = client.get_partitions_ps_with_auth(db_name,
        tbl_name, part_vals, max_parts, user_name, group_names);
    return fastpath ? parts : deepCopyPartitions(filterHook.filterPartitions(parts));
  }

  /**
   * Get list of partitions matching specified filter
   * @param db_name the database name
   * @param tbl_name the table name
   * @param filter the filter string,
   *    for example "part1 = \"p1_abc\" and part2 &lt;= "\p2_test\"". Filtering can
   *    be done only on string partition keys.
   * @param max_parts the maximum number of partitions to return,
   *    all partitions are returned if -1 is passed
   * @return list of partitions
   * @throws MetaException
   * @throws NoSuchObjectException
   * @throws TException
   */
  @Override
  public List<Partition> listPartitionsByFilter(String db_name, String tbl_name,
      String filter, short max_parts) throws MetaException,
         NoSuchObjectException, TException {
    List<Partition> parts = client.get_partitions_by_filter(db_name, tbl_name, filter, max_parts);
    return fastpath ? parts :deepCopyPartitions(filterHook.filterPartitions(parts));
  }

  @Override
  public PartitionSpecProxy listPartitionSpecsByFilter(String db_name, String tbl_name,
                                                       String filter, int max_parts) throws TException {
    return PartitionSpecProxy.Factory.get(filterHook.filterPartitionSpecs(
        client.get_part_specs_by_filter(db_name, tbl_name, filter, max_parts)));
  }

  @Override
  public boolean listPartitionsByExpr(String db_name, String tbl_name, byte[] expr,
      String default_partition_name, short max_parts, List<Partition> result)
          throws TException {
    assert result != null;
    PartitionsByExprRequest req = new PartitionsByExprRequest(
        db_name, tbl_name, ByteBuffer.wrap(expr));
    if (default_partition_name != null) {
      req.setDefaultPartitionName(default_partition_name);
    }
    if (max_parts >= 0) {
      req.setMaxParts(max_parts);
    }
    PartitionsByExprResult r;
    try {
      r = client.get_partitions_by_expr(req);
    } catch (TApplicationException te) {
      // TODO: backward compat for Hive <= 0.12. Can be removed later.
      if (te.getType() != TApplicationException.UNKNOWN_METHOD
          && te.getType() != TApplicationException.WRONG_METHOD_NAME) {
        throw te;
      }
      throw new IncompatibleMetastoreException(
          "Metastore doesn't support listPartitionsByExpr: " + te.getMessage());
    }
    if (fastpath) {
      result.addAll(r.getPartitions());
    } else {
      r.setPartitions(filterHook.filterPartitions(r.getPartitions()));
      // TODO: in these methods, do we really need to deepcopy?
      deepCopyPartitions(r.getPartitions(), result);
    }
    return !r.isSetHasUnknownPartitions() || r.isHasUnknownPartitions(); // Assume the worst.
  }


  @Override
  public boolean listPartitionsSpecByExpr(PartitionsByExprRequest req, List<PartitionSpec> result)
      throws TException {
    assert result != null;
    PartitionsSpecByExprResult r;
    try {
      r = client.get_partitions_spec_by_expr(req);
    } catch (TApplicationException te) {
      if (te.getType() != TApplicationException.UNKNOWN_METHOD
          && te.getType() != TApplicationException.WRONG_METHOD_NAME) {
        throw te;
      }
      throw new IncompatibleMetastoreException(
          "Metastore doesn't support listPartitionsByExpr: " + te.getMessage());
    }

    // do client side filtering
    r.setPartitionsSpec(filterHook.filterPartitionSpecs(r.getPartitionsSpec()));

    result.addAll(r.getPartitionsSpec());
    return !r.isSetHasUnknownPartitions() || r.isHasUnknownPartitions(); // Assume the worst.
  }

  /**
   * @param name
   * @return the database
   * @throws NoSuchObjectException
   * @throws MetaException
   * @throws TException
   * @see org.apache.hadoop.hive.metastore.api.ThriftHiveMetastore.Iface#get_database(java.lang.String)
   */
  @Override
  public Database getDatabase(String name) throws NoSuchObjectException,
      MetaException, TException {
    Database d = client.get_database(name);
    return fastpath ? d :deepCopy(filterHook.filterDatabase(d));
  }

  @Override
  public DataConnector getDataConnector(String name) throws TException {
    GetDataConnectorRequest request = new GetDataConnectorRequest();
    request.setConnectorName(name);
    return client.get_dataconnector_req(request); // TODO run thru filterhook
  }

  /**
   * @param tbl_name
   * @param db_name
   * @param part_vals
   * @return the partition
   * @throws MetaException
   * @throws TException
   * @see org.apache.hadoop.hive.metastore.api.ThriftHiveMetastore.Iface#get_partition(java.lang.String,
   *      java.lang.String, java.util.List)
   */
  @Override
  public Partition getPartition(String db_name, String tbl_name,
      List<String> part_vals) throws MetaException, TException {
    Partition p = client.get_partition(db_name, tbl_name, part_vals);
    return fastpath ? p : deepCopy(filterHook.filterPartition(p));
  }

  @Override
  public GetPartitionResponse getPartitionRequest(GetPartitionRequest req) throws TException {
    return client.get_partition_req(req);
  }

  @Override
  public List<Partition> getPartitionsByNames(String db_name, String tbl_name,
                                              List<String> part_names) throws TException {
    GetPartitionsByNamesRequest gpbnr = new GetPartitionsByNamesRequest(db_name, tbl_name);
    gpbnr.setNames(part_names);
    List<Partition> parts = client.get_partitions_by_names_req(gpbnr).getPartitions();
    return fastpath ? parts : deepCopyPartitions(filterHook.filterPartitions(parts));
  }

  @Override public PartitionsResponse getPartitionsRequest(PartitionsRequest req) throws TException {
    return client.get_partitions_req(req);
  }

  @Override
  public List<String> listPartitionNames(PartitionsByExprRequest request) throws TException {
    throw new UnsupportedOperationException();
  }

  @Override
  public PartitionValuesResponse listPartitionValues(PartitionValuesRequest request) throws TException {
    return client.get_partition_values(request);
  }

  @Override
  public Partition getPartitionWithAuthInfo(String db_name, String tbl_name,
      List<String> part_vals, String user_name, List<String> group_names) throws TException {
    Partition p = client.get_partition_with_auth(db_name, tbl_name, part_vals, user_name,
        group_names);
    return fastpath ? p : deepCopy(filterHook.filterPartition(p));
  }

  /**
   * @param name
   * @param dbname
   * @return the table
   * @throws NoSuchObjectException
   * @throws MetaException
   * @throws TException
   * @throws NoSuchObjectException
   * @see org.apache.hadoop.hive.metastore.api.ThriftHiveMetastore.Iface#get_table(java.lang.String,
   *      java.lang.String)
   */
  @Override
  public Table getTable(String dbname, String name) throws MetaException,
      TException, NoSuchObjectException {
    GetTableRequest req = new GetTableRequest(dbname, name);
    req.setCapabilities(version);
    Table t = client.get_table_req(req).getTable();
    return fastpath ? t : deepCopy(filterHook.filterTable(t));
  }

  /** {@inheritDoc} */
  @Override
  public List<Table> getTableObjectsByName(String dbName, List<String> tableNames) throws TException {
    GetTablesRequest req = new GetTablesRequest(dbName);
    req.setTblNames(tableNames);
    req.setCapabilities(version);
    List<Table> tabs = client.get_table_objects_by_name_req(req).getTables();
    return fastpath ? tabs : deepCopyTables(filterHook.filterTables(tabs));
  }

  /** {@inheritDoc} */
  @Override
  public List<ExtendedTableInfo> getTablesExt(String catName, String dbName, String tablePattern,
                 int requestedFields, int limit) throws TException {
    GetTablesExtRequest req = new GetTablesExtRequest(catName, dbName, tablePattern, requestedFields);
    req.setLimit(limit);
    return client.get_tables_ext(req);
  }

  /** {@inheritDoc} */
  @Override
  public Materialization getMaterializationInvalidationInfo(CreationMetadata cm, String validTxnList)
          throws TException {
    return client.get_materialization_invalidation_info(cm, validTxnList);
  }

  /** {@inheritDoc} */
  @Override
  public void updateCreationMetadata(String dbName, String tableName, CreationMetadata cm)
      throws TException {
    client.update_creation_metadata(null, dbName, tableName, cm);
  }

  /** {@inheritDoc} */
  @Override
  public List<String> listTableNamesByFilter(String dbName, String filter, short maxTables)
      throws TException {
    return filterHook.filterTableNames(null, dbName,
        client.get_table_names_by_filter(dbName, filter, maxTables));
  }

  /**
   * @param name
   * @return the type
   * @throws MetaException
   * @throws TException
   * @throws NoSuchObjectException
   * @see org.apache.hadoop.hive.metastore.api.ThriftHiveMetastore.Iface#get_type(java.lang.String)
   */
  public Type getType(String name) throws NoSuchObjectException, MetaException, TException {
    return deepCopy(client.get_type(name));
  }

  /** {@inheritDoc} */
  @Override
  public List<String> getTables(String dbname, String tablePattern) throws MetaException {
    try {
      return filterHook.filterTableNames(null, dbname, client.get_tables(dbname, tablePattern));
    } catch (Exception e) {
      MetaStoreUtils.throwMetaException(e);
    }
    return null;
  }

  /** {@inheritDoc} */
  @Override
  public List<String> getTables(String dbname, String tablePattern, TableType tableType) throws MetaException {
    try {
      return filterHook.filterTableNames(null, dbname,
          client.get_tables_by_type(dbname, tablePattern, tableType.toString()));
    } catch (Exception e) {
      MetaStoreUtils.throwMetaException(e);
    }
    return null;
  }

  /** {@inheritDoc} */
  @Override
  public List<Table> getAllMaterializedViewObjectsForRewriting() throws MetaException {
    try {
      return filterHook.filterTables(client.get_all_materialized_view_objects_for_rewriting());
    } catch (Exception e) {
      MetaStoreUtils.throwMetaException(e);
    }
    return null;
  }

  /** {@inheritDoc} */
  @Override
  public List<String> getMaterializedViewsForRewriting(String dbname) throws MetaException {
    try {
      return filterHook.filterTableNames(null, dbname, client.get_materialized_views_for_rewriting(dbname));
    } catch (Exception e) {
      MetaStoreUtils.throwMetaException(e);
    }
    return null;
  }

  @Override
  public List<TableMeta> getTableMeta(String dbPatterns, String tablePatterns, List<String> tableTypes)
      throws MetaException {
    try {
      return filterNames(client.get_table_meta(dbPatterns, tablePatterns, tableTypes));
    } catch (Exception e) {
      MetaStoreUtils.throwMetaException(e);
    }
    return null;
  }

  private List<TableMeta> filterNames(List<TableMeta> metas) throws MetaException {
    Map<String, TableMeta> sources = new LinkedHashMap<>();
    Map<String, List<String>> dbTables = new LinkedHashMap<>();
    for (TableMeta meta : metas) {
      sources.put(meta.getDbName() + "." + meta.getTableName(), meta);
      List<String> tables = dbTables.get(meta.getDbName());
      if (tables == null) {
        dbTables.put(meta.getDbName(), tables = new ArrayList<>());
      }
      tables.add(meta.getTableName());
    }
    List<TableMeta> filtered = new ArrayList<>();
    for (Map.Entry<String, List<String>> entry : dbTables.entrySet()) {
      for (String table : filterHook.filterTableNames(null, entry.getKey(), entry.getValue())) {
        filtered.add(sources.get(entry.getKey() + "." + table));
      }
    }
    return filtered;
  }

  /** {@inheritDoc} */
  @Override
  public List<String> getAllTables(String dbname) throws MetaException {
    try {
      return filterHook.filterTableNames(null, dbname, client.get_all_tables(dbname));
    } catch (Exception e) {
      MetaStoreUtils.throwMetaException(e);
    }
    return null;
  }

  @Override
  public boolean tableExists(String databaseName, String tableName) throws TException {
    try {
      GetTableRequest req = new GetTableRequest(databaseName, tableName);
      req.setCapabilities(version);
      return filterHook.filterTable(client.get_table_req(req).getTable()) != null;
    } catch (NoSuchObjectException e) {
      return false;
    }
  }

  @Override
  public List<String> listPartitionNames(String dbName, String tblName, short max)
          throws TException {
    return filterHook.filterPartitionNames(null, dbName, tblName,
        client.get_partition_names(dbName, tblName, max));
  }

  @Override public GetPartitionNamesPsResponse listPartitionNamesRequest(GetPartitionNamesPsRequest req)
      throws TException {
    return client.get_partition_names_ps_req(req);
  }

  @Override
  public List<String> listPartitionNames(String db_name, String tbl_name,
      List<String> part_vals, short max_parts) throws TException {
    return filterHook.filterPartitionNames(null, db_name, tbl_name,
        client.get_partition_names_ps(db_name, tbl_name, part_vals, max_parts));
  }

  /**
   * Get number of partitions matching specified filter
   * @param db_name the database name
   * @param tbl_name the table name
   * @param filter the filter string,
   *    for example "part1 = \"p1_abc\" and part2 &lt;= "\p2_test\"". Filtering can
   *    be done only on string partition keys.
   * @return number of partitions
   * @throws MetaException
   * @throws NoSuchObjectException
   * @throws TException
   */
  @Override
  public int getNumPartitionsByFilter(String db_name, String tbl_name,
                                      String filter) throws MetaException,
          NoSuchObjectException, TException {
    return client.get_num_partitions_by_filter(db_name, tbl_name, filter);
  }

  @Override
  public void alter_partition(String dbName, String tblName, Partition newPart) throws TException {
    client.alter_partition_with_environment_context(dbName, tblName, newPart, null);
  }

  @Override
  public void alter_partition(String dbName, String tblName, Partition newPart, EnvironmentContext environmentContext)
      throws TException {
    client.alter_partition_with_environment_context(dbName, tblName, newPart, environmentContext);
  }

  @Override
  public void alter_partitions(String dbName, String tblName, List<Partition> newParts) throws TException {
    client.alter_partitions(dbName, tblName, newParts);
  }

  @Override
  public void alter_partitions(String dbName, String tblName, List<Partition> newParts, EnvironmentContext environmentContext)
  throws TException {
    AlterPartitionsRequest req = new AlterPartitionsRequest();
    req.setDbName(dbName);
    req.setTableName(tblName);
    req.setPartitions(newParts);
    req.setEnvironmentContext(environmentContext);
    client.alter_partitions_req(req);
  }

  @Override
  public void alter_partitions(String dbName, String tblName, List<Partition> newParts,
                               EnvironmentContext environmentContext,
                               String writeIdList, long writeId) throws TException {
    AlterPartitionsRequest req = new AlterPartitionsRequest();
    req.setDbName(dbName);
    req.setTableName(tblName);
    req.setPartitions(newParts);
    req.setEnvironmentContext(environmentContext);
    req.setValidWriteIdList(writeIdList);
    client.alter_partitions_req(req);
  }

  @Override
  public void alterDatabase(String dbName, Database db) throws TException {
    client.alter_database(dbName, db);
  }

  @Override
  public void alterDataConnector(String dcName, DataConnector connector) throws TException {
    AlterDataConnectorRequest alterReq = new AlterDataConnectorRequest(dcName, connector);
    client.alter_dataconnector_req(alterReq);
  }

  /**
   * @param db
   * @param tableName
   * @throws UnknownTableException
   * @throws UnknownDBException
   * @throws MetaException
   * @throws TException
   * @see org.apache.hadoop.hive.metastore.api.ThriftHiveMetastore.Iface#get_fields(java.lang.String,
   *      java.lang.String)
   */
  @Override
  public List<FieldSchema> getFields(String db, String tableName)
      throws MetaException, TException, UnknownTableException,
      UnknownDBException {
    List<FieldSchema> fields = client.get_fields(db, tableName);
    return fastpath ? fields : deepCopyFieldSchemas(fields);
  }

  @Override
  public List<SQLPrimaryKey> getPrimaryKeys(PrimaryKeysRequest req) throws TException {
    return client.get_primary_keys(req).getPrimaryKeys();
  }

  @Override
  public List<SQLForeignKey> getForeignKeys(ForeignKeysRequest req) throws TException {
    return client.get_foreign_keys(req).getForeignKeys();
  }

  @Override
  public List<SQLUniqueConstraint> getUniqueConstraints(UniqueConstraintsRequest req) throws TException {
    return client.get_unique_constraints(req).getUniqueConstraints();
  }

  @Override
  public List<SQLNotNullConstraint> getNotNullConstraints(NotNullConstraintsRequest req) throws TException {
    return client.get_not_null_constraints(req).getNotNullConstraints();
  }

  @Override
  public List<SQLDefaultConstraint> getDefaultConstraints(DefaultConstraintsRequest req) throws TException {
    return client.get_default_constraints(req).getDefaultConstraints();
  }

  @Override
  public List<SQLCheckConstraint> getCheckConstraints(CheckConstraintsRequest request) throws TException {
    return client.get_check_constraints(request).getCheckConstraints();
  }

  @Override
  public SQLAllTableConstraints getAllTableConstraints(AllTableConstraintsRequest request) throws TException {
    return client.get_all_table_constraints(request).getAllTableConstraints();
  }

  /** {@inheritDoc} */
  @Override
  @Deprecated
  //use setPartitionColumnStatistics instead
  public boolean updateTableColumnStatistics(ColumnStatistics statsObj) throws TException {
    return client.update_table_column_statistics(statsObj);
  }

  /** {@inheritDoc} */
  @Override
  @Deprecated
  //use setPartitionColumnStatistics instead
  public boolean updatePartitionColumnStatistics(ColumnStatistics statsObj) throws TException {
    return client.update_partition_column_statistics(statsObj);
  }

  /** {@inheritDoc} */
  @Override
  public boolean setPartitionColumnStatistics(SetPartitionsStatsRequest request) throws TException {
    return client.set_aggr_stats_for(request);
  }

  @Override
  public void flushCache() {
    try {
      client.flushCache();
    } catch (TException e) {
      // Not much we can do about it honestly
      LOG.warn("Got error flushing the cache", e);
    }
  }

  /** {@inheritDoc} */
  @Override
  public List<ColumnStatisticsObj> getTableColumnStatistics(String dbName, String tableName,
      List<String> colNames, String engine) throws TException {
    TableStatsRequest tsr = new TableStatsRequest(dbName, tableName, colNames);
    tsr.setEngine(engine);
    return client.get_table_statistics_req(new TableStatsRequest(tsr)).getTableStats();
  }

  @Override
  public List<ColumnStatisticsObj> getTableColumnStatistics(
      String dbName, String tableName, List<String> colNames, String engine, String validWriteIdList)
      throws TException {
    TableStatsRequest tsr = new TableStatsRequest(dbName, tableName, colNames);
    tsr.setEngine(engine);
    tsr.setValidWriteIdList(validWriteIdList);

    return client.get_table_statistics_req(tsr).getTableStats();
  }

  /** {@inheritDoc} */
  @Override
  public Map<String, List<ColumnStatisticsObj>> getPartitionColumnStatistics(
      String dbName, String tableName, List<String> partNames, List<String> colNames, String engine)
          throws TException {
    PartitionsStatsRequest psr = new PartitionsStatsRequest(dbName, tableName, colNames, partNames);
    psr.setEngine(engine);
    return client.get_partitions_statistics_req(new PartitionsStatsRequest(psr)).getPartStats();
  }

  @Override
  public Map<String, List<ColumnStatisticsObj>> getPartitionColumnStatistics(
      String dbName, String tableName, List<String> partNames,
      List<String> colNames, String engine, String validWriteIdList)
      throws TException {
    PartitionsStatsRequest psr = new PartitionsStatsRequest(dbName, tableName, colNames, partNames);
    psr.setEngine(engine);
    psr.setValidWriteIdList(validWriteIdList);
    return client.get_partitions_statistics_req(
        psr).getPartStats();
  }

  @Override
  public boolean deleteColumnStatistics(DeleteColumnStatisticsRequest req)
          throws TException {
    return client.delete_column_statistics_req(req);
  }

  @Override
  public void updateTransactionalStatistics(UpdateTransactionalStatsRequest req)  throws TException {
    client.update_transaction_statistics(req);
  }

  /**
   * @param db
   * @param tableName
   * @throws UnknownTableException
   * @throws UnknownDBException
   * @throws MetaException
   * @throws TException
   * @see org.apache.hadoop.hive.metastore.api.ThriftHiveMetastore.Iface#get_schema(java.lang.String,
   *      java.lang.String)
   */
  @Override
  public List<FieldSchema> getSchema(String db, String tableName)
      throws MetaException, TException, UnknownTableException,
      UnknownDBException {
      EnvironmentContext envCxt = null;
      String addedJars = MetastoreConf.getVar(conf, ConfVars.ADDED_JARS);
      if(org.apache.commons.lang3.StringUtils.isNotBlank(addedJars)) {
         Map<String, String> props = new HashMap<String, String>();
         props.put("hive.added.jars.path", addedJars);
         envCxt = new EnvironmentContext(props);
       }

    List<FieldSchema> fields = client.get_schema_with_environment_context(db, tableName, envCxt);
    return fastpath ? fields : deepCopyFieldSchemas(fields);
  }

  @Override
  public String getConfigValue(String name, String defaultValue) throws TException {
    return client.get_config_value(name, defaultValue);
  }

  @Override
  public Partition getPartition(String db, String tableName, String partName) throws TException {
    Partition p = client.get_partition_by_name(db, tableName, partName);
    return fastpath ? p : deepCopy(filterHook.filterPartition(p));
  }

  public Partition appendPartitionByName(String dbName, String tableName, String partName) throws TException {
    return appendPartitionByName(dbName, tableName, partName, null);
  }

  public Partition appendPartitionByName(String dbName, String tableName, String partName,
      EnvironmentContext envContext) throws TException {
    Partition p = client.append_partition_by_name_with_environment_context(dbName, tableName,
        partName, envContext);
    return fastpath ? p : deepCopy(p);
  }

  public boolean dropPartitionByName(String dbName, String tableName, String partName,
      boolean deleteData) throws TException {
    return dropPartitionByName(dbName, tableName, partName, deleteData, null);
  }

  public boolean dropPartitionByName(String dbName, String tableName, String partName,
      boolean deleteData, EnvironmentContext envContext) throws TException {
    return client.drop_partition_by_name_with_environment_context(dbName, tableName, partName,
        deleteData, envContext);
  }

  private HiveMetaHook getHook(Table tbl) throws MetaException {
    if (hookLoader == null) {
      return null;
    }
    return hookLoader.getHook(tbl);
  }

  @Override
  public List<String> partitionNameToVals(String name) throws TException {
    return client.partition_name_to_vals(name);
  }

  @Override
  public Map<String, String> partitionNameToSpec(String name) throws TException{
    return client.partition_name_to_spec(name);
  }

  /**
   * @param partition
   * @return
   */
  private Partition deepCopy(Partition partition) {
    Partition copy = null;
    if (partition != null) {
      copy = new Partition(partition);
    }
    return copy;
  }

  private Database deepCopy(Database database) {
    Database copy = null;
    if (database != null) {
      copy = new Database(database);
    }
    return copy;
  }

  protected Table deepCopy(Table table) {
    Table copy = null;
    if (table != null) {
      copy = new Table(table);
    }
    return copy;
  }

  private Type deepCopy(Type type) {
    Type copy = null;
    if (type != null) {
      copy = new Type(type);
    }
    return copy;
  }

  private FieldSchema deepCopy(FieldSchema schema) {
    FieldSchema copy = null;
    if (schema != null) {
      copy = new FieldSchema(schema);
    }
    return copy;
  }

  private Function deepCopy(Function func) {
    Function copy = null;
    if (func != null) {
      copy = new Function(func);
    }
    return copy;
  }

  protected PrincipalPrivilegeSet deepCopy(PrincipalPrivilegeSet pps) {
    PrincipalPrivilegeSet copy = null;
    if (pps != null) {
      copy = new PrincipalPrivilegeSet(pps);
    }
    return copy;
  }

  private List<Partition> deepCopyPartitions(List<Partition> partitions) {
    return deepCopyPartitions(partitions, null);
  }

  private List<Partition> deepCopyPartitions(
      Collection<Partition> src, List<Partition> dest) {
    if (src == null) {
      return dest;
    }
    if (dest == null) {
      dest = new ArrayList<Partition>(src.size());
    }
    for (Partition part : src) {
      dest.add(deepCopy(part));
    }
    return dest;
  }

  private List<Table> deepCopyTables(List<Table> tables) {
    List<Table> copy = null;
    if (tables != null) {
      copy = new ArrayList<Table>();
      for (Table tab : tables) {
        copy.add(deepCopy(tab));
      }
    }
    return copy;
  }

  protected List<FieldSchema> deepCopyFieldSchemas(List<FieldSchema> schemas) {
    List<FieldSchema> copy = null;
    if (schemas != null) {
      copy = new ArrayList<FieldSchema>();
      for (FieldSchema schema : schemas) {
        copy.add(deepCopy(schema));
      }
    }
    return copy;
  }

  @Override
  public boolean grant_role(String roleName, String userName,
      PrincipalType principalType, String grantor, PrincipalType grantorType,
      boolean grantOption) throws TException {
    GrantRevokeRoleRequest req = new GrantRevokeRoleRequest();
    req.setRequestType(GrantRevokeType.GRANT);
    req.setRoleName(roleName);
    req.setPrincipalName(userName);
    req.setPrincipalType(principalType);
    req.setGrantor(grantor);
    req.setGrantorType(grantorType);
    req.setGrantOption(grantOption);
    GrantRevokeRoleResponse res = client.grant_revoke_role(req);
    if (!res.isSetSuccess()) {
      throw new MetaException("GrantRevokeResponse missing success field");
    }
    return res.isSuccess();
  }

  @Override
  public boolean create_role(Role role) throws TException {
    return client.create_role(role);
  }

  @Override
  public boolean drop_role(String roleName) throws TException {
    return client.drop_role(roleName);
  }

  @Override
  public List<Role> list_roles(String principalName,
      PrincipalType principalType) throws TException {
    return client.list_roles(principalName, principalType);
  }

  @Override
  public List<String> listRoleNames() throws TException {
    return client.get_role_names();
  }

  @Override
  public GetPrincipalsInRoleResponse get_principals_in_role(GetPrincipalsInRoleRequest req)
      throws TException {
    return client.get_principals_in_role(req);
  }

  @Override
  public GetRoleGrantsForPrincipalResponse get_role_grants_for_principal(
      GetRoleGrantsForPrincipalRequest getRolePrincReq) throws TException {
    return client.get_role_grants_for_principal(getRolePrincReq);
  }

  @Override
  public boolean grant_privileges(PrivilegeBag privileges) throws TException {
    GrantRevokePrivilegeRequest req = new GrantRevokePrivilegeRequest();
    req.setRequestType(GrantRevokeType.GRANT);
    req.setPrivileges(privileges);
    GrantRevokePrivilegeResponse res = client.grant_revoke_privileges(req);
    if (!res.isSetSuccess()) {
      throw new MetaException("GrantRevokePrivilegeResponse missing success field");
    }
    return res.isSuccess();
  }

  @Override
  public boolean revoke_role(String roleName, String userName,
      PrincipalType principalType, boolean grantOption) throws TException {
    GrantRevokeRoleRequest req = new GrantRevokeRoleRequest();
    req.setRequestType(GrantRevokeType.REVOKE);
    req.setRoleName(roleName);
    req.setPrincipalName(userName);
    req.setPrincipalType(principalType);
    req.setGrantOption(grantOption);
    GrantRevokeRoleResponse res = client.grant_revoke_role(req);
    if (!res.isSetSuccess()) {
      throw new MetaException("GrantRevokeResponse missing success field");
    }
    return res.isSuccess();
  }

  @Override
  public boolean revoke_privileges(PrivilegeBag privileges, boolean grantOption) throws TException {
    GrantRevokePrivilegeRequest req = new GrantRevokePrivilegeRequest();
    req.setRequestType(GrantRevokeType.REVOKE);
    req.setPrivileges(privileges);
    req.setRevokeGrantOption(grantOption);
    GrantRevokePrivilegeResponse res = client.grant_revoke_privileges(req);
    if (!res.isSetSuccess()) {
      throw new MetaException("GrantRevokePrivilegeResponse missing success field");
    }
    return res.isSuccess();
  }

  @Override
  public boolean refresh_privileges(HiveObjectRef objToRefresh, String authorizer,
      PrivilegeBag grantPrivileges) throws TException {
    String defaultCat = getDefaultCatalog(conf);
    objToRefresh.setCatName(defaultCat);

    if (grantPrivileges.getPrivileges() != null) {
      for (HiveObjectPrivilege priv : grantPrivileges.getPrivileges()) {
        if (!priv.getHiveObject().isSetCatName()) {
          priv.getHiveObject().setCatName(defaultCat);
        }
      }
    }
    GrantRevokePrivilegeRequest grantReq = new GrantRevokePrivilegeRequest();
    grantReq.setRequestType(GrantRevokeType.GRANT);
    grantReq.setPrivileges(grantPrivileges);

    GrantRevokePrivilegeResponse res = client.refresh_privileges(objToRefresh, authorizer, grantReq);
    if (!res.isSetSuccess()) {
      throw new MetaException("GrantRevokePrivilegeResponse missing success field");
    }
    return res.isSuccess();
  }

  @Override
  public PrincipalPrivilegeSet get_privilege_set(HiveObjectRef hiveObject,
      String userName, List<String> groupNames) throws TException {
    return client.get_privilege_set(hiveObject, userName, groupNames);
  }

  @Override
  public List<HiveObjectPrivilege> list_privileges(String principalName,
      PrincipalType principalType, HiveObjectRef hiveObject) throws TException {
    return client.list_privileges(principalName, principalType, hiveObject);
  }

  public String getDelegationToken(String renewerKerberosPrincipalName) throws
          TException, IOException {
    //a convenience method that makes the intended owner for the delegation
    //token request the current user
    String owner = SecurityUtils.getUser();
    return getDelegationToken(owner, renewerKerberosPrincipalName);
  }

  @Override
  public String getDelegationToken(String owner, String renewerKerberosPrincipalName) throws
          TException {
    // This is expected to be a no-op, so we will return null when we use local metastore.
    if (localMetaStore) {
      return null;
    }
    return client.get_delegation_token(owner, renewerKerberosPrincipalName);
  }

  @Override
  public long renewDelegationToken(String tokenStrForm) throws TException {
    if (localMetaStore) {
      return 0;
    }
    return client.renew_delegation_token(tokenStrForm);

  }

  @Override
  public void cancelDelegationToken(String tokenStrForm) throws TException {
    if (localMetaStore) {
      return;
    }
    client.cancel_delegation_token(tokenStrForm);
  }

  @Override
  public boolean addToken(String tokenIdentifier, String delegationToken) throws TException {
     return client.add_token(tokenIdentifier, delegationToken);
  }

  @Override
  public boolean removeToken(String tokenIdentifier) throws TException {
    return client.remove_token(tokenIdentifier);
  }

  @Override
  public String getToken(String tokenIdentifier) throws TException {
    return client.get_token(tokenIdentifier);
  }

  @Override
  public List<String> getAllTokenIdentifiers() throws TException {
    return client.get_all_token_identifiers();
  }

  @Override
  public int addMasterKey(String key) throws TException {
    return client.add_master_key(key);
  }

  @Override
  public void updateMasterKey(Integer seqNo, String key) throws TException {
    client.update_master_key(seqNo, key);
  }

  @Override
  public boolean removeMasterKey(Integer keySeq) throws TException {
    return client.remove_master_key(keySeq);
  }

  @Override
  public String[] getMasterKeys() throws TException {
    List<String> keyList = client.get_master_keys();
    return keyList.toArray(new String[keyList.size()]);
  }

  @Override
  public GetOpenTxnsResponse getOpenTxns() throws TException {
    GetOpenTxnsRequest getOpenTxnsRequest = new GetOpenTxnsRequest();
    getOpenTxnsRequest.setExcludeTxnTypes(Collections.singletonList(TxnType.READ_ONLY));
    return client.get_open_txns_req(getOpenTxnsRequest);
  }

  @Override
  public ValidTxnList getValidTxns() throws TException {
    GetOpenTxnsRequest getOpenTxnsRequest = new GetOpenTxnsRequest();
    getOpenTxnsRequest.setExcludeTxnTypes(Arrays.asList(TxnType.READ_ONLY));
    return TxnCommonUtils.createValidReadTxnList(client.get_open_txns_req(getOpenTxnsRequest), 0);
  }

  @Override
  public ValidTxnList getValidTxns(long currentTxn) throws TException {
    GetOpenTxnsRequest getOpenTxnsRequest = new GetOpenTxnsRequest();
    getOpenTxnsRequest.setExcludeTxnTypes(Arrays.asList(TxnType.READ_ONLY));
    return TxnCommonUtils.createValidReadTxnList(client.get_open_txns_req(getOpenTxnsRequest), currentTxn);
  }

  @Override
  public ValidTxnList getValidTxns(long currentTxn, List<TxnType> excludeTxnTypes) throws TException {
    GetOpenTxnsRequest getOpenTxnsRequest = new GetOpenTxnsRequest();
    getOpenTxnsRequest.setExcludeTxnTypes(excludeTxnTypes);
    return TxnCommonUtils.createValidReadTxnList(client.get_open_txns_req(getOpenTxnsRequest),
      currentTxn);
  }

  @Override
  public ValidWriteIdList getValidWriteIds(String fullTableName) throws TException {
    GetValidWriteIdsRequest rqst = new GetValidWriteIdsRequest(Collections.singletonList(fullTableName));
    GetValidWriteIdsResponse validWriteIds = client.get_valid_write_ids(rqst);
    return TxnCommonUtils.createValidReaderWriteIdList(validWriteIds.getTblValidWriteIds().get(0));
  }

  @Override
  public ValidWriteIdList getValidWriteIds(String fullTableName, Long writeId) throws TException {
    GetValidWriteIdsRequest rqst = new GetValidWriteIdsRequest(Collections.singletonList(fullTableName));
    rqst.setWriteId(writeId);
    GetValidWriteIdsResponse validWriteIds = client.get_valid_write_ids(rqst);
    return TxnCommonUtils.createValidReaderWriteIdList(validWriteIds.getTblValidWriteIds().get(0));
  }

  @Override
  public List<TableValidWriteIds> getValidWriteIds(List<String> tablesList, String validTxnList)
          throws TException {
    GetValidWriteIdsRequest rqst = new GetValidWriteIdsRequest(tablesList);
    rqst.setValidTxnList(validTxnList);
    return client.get_valid_write_ids(rqst).getTblValidWriteIds();
  }

  @Override
  public void addWriteIdsToMinHistory(long txnId, Map<String, Long> writeIds) throws TException {
    client.add_write_ids_to_min_history(txnId, writeIds);
  }

  @Override
  public long openTxn(String user) throws TException {
    OpenTxnsResponse txns = openTxns(user, 1);
    return txns.getTxn_ids().get(0);
  }

  @Override
  public long openTxn(String user, TxnType txnType) throws TException {
    OpenTxnsResponse txns = openTxnsIntr(user, 1, null, null, txnType);
    return txns.getTxn_ids().get(0);
  }

  @Override
  public OpenTxnsResponse openTxns(String user, int numTxns) throws TException {
    return openTxnsIntr(user, numTxns, null, null, null);
  }

  @Override
  public List<Long> replOpenTxn(String replPolicy, List<Long> srcTxnIds, String user, TxnType txnType) throws TException {
    // As this is called from replication task, the user is the user who has fired the repl command.
    // This is required for standalone metastore authentication.
    OpenTxnsResponse txns = openTxnsIntr(user, srcTxnIds != null ? srcTxnIds.size() : 1, replPolicy, srcTxnIds, txnType);
    return txns.getTxn_ids();
  }

  private OpenTxnsResponse openTxnsIntr(String user, int numTxns, String replPolicy,
                                        List<Long> srcTxnIds, TxnType txnType) throws TException {
    String hostname;
    try {
      hostname = InetAddress.getLocalHost().getHostName();
    } catch (UnknownHostException e) {
      LOG.error("Unable to resolve my host name " + e.getMessage());
      throw new RuntimeException(e);
    }
    OpenTxnRequest rqst = new OpenTxnRequest(numTxns, user, hostname);
    if (replPolicy != null) {
      rqst.setReplPolicy(replPolicy);
      if (txnType == TxnType.REPL_CREATED) {
        assert srcTxnIds != null;
        assert numTxns == srcTxnIds.size();
        rqst.setReplSrcTxnIds(srcTxnIds);
      }
    } else {
      assert srcTxnIds == null;
    }
    if (txnType != null) {
      rqst.setTxn_type(txnType);
    }
    return client.open_txns(rqst);
  }

  @Override
  public void rollbackTxn(long txnid) throws TException {
    client.abort_txn(new AbortTxnRequest(txnid));
  }

  @Override
  public void rollbackTxn(AbortTxnRequest abortTxnRequest) throws TException {
    client.abort_txn(abortTxnRequest);
  }

  @Override
  public void replRollbackTxn(long srcTxnId, String replPolicy, TxnType txnType) throws TException {
    AbortTxnRequest rqst = new AbortTxnRequest(srcTxnId);
    rqst.setReplPolicy(replPolicy);
    rqst.setTxn_type(txnType);
    client.abort_txn(rqst);
  }

  @Override
  public void commitTxn(long txnid) throws TException {
    client.commit_txn(new CommitTxnRequest(txnid));
  }

  @Override
  public void commitTxnWithKeyValue(long txnid, long tableId, String key, String value)
          throws TException {
    CommitTxnRequest ctr = new CommitTxnRequest(txnid);
    Preconditions.checkNotNull(key, "The key to commit together"
        + " with the transaction can't be null");
    Preconditions.checkNotNull(value, "The value to commit together"
        + " with the transaction can't be null");
    ctr.setKeyValue(new CommitTxnKeyValue(tableId, key, value));
    client.commit_txn(ctr);
  }

  @Override
  public void commitTxn(CommitTxnRequest rqst) throws TException {
    client.commit_txn(rqst);
  }

  @Override
  public GetOpenTxnsInfoResponse showTxns() throws TException {
    return client.get_open_txns_info();
  }

  @Override
  public void abortTxns(List<Long> txnids) throws TException {
    client.abort_txns(new AbortTxnsRequest(txnids));
  }

  @Override
  public void abortTxns(AbortTxnsRequest abortTxnsRequest) throws TException {
    client.abort_txns(abortTxnsRequest);
  }

  @Override
  public void replTableWriteIdState(String validWriteIdList, String dbName, String tableName, List<String> partNames)
          throws TException {
    String user;
    try {
      user = UserGroupInformation.getCurrentUser().getUserName();
    } catch (IOException e) {
      LOG.error("Unable to resolve current user name " + e.getMessage());
      throw new RuntimeException(e);
    }

    String hostName;
    try {
      hostName = InetAddress.getLocalHost().getHostName();
    } catch (UnknownHostException e) {
      LOG.error("Unable to resolve my host name " + e.getMessage());
      throw new RuntimeException(e);
    }

    ReplTblWriteIdStateRequest rqst
            = new ReplTblWriteIdStateRequest(validWriteIdList, user, hostName, dbName, tableName);
    if (partNames != null) {
      rqst.setPartNames(partNames);
    }
    client.repl_tbl_writeid_state(rqst);
  }
  @Override
  public long allocateTableWriteId(long txnId, String dbName, String tableName, boolean shouldRealloc) throws TException {
    return allocateTableWriteIdsBatch(Collections.singletonList(txnId), dbName, tableName, shouldRealloc).get(0).getWriteId();
  }

  @Override
  public long allocateTableWriteId(long txnId, String dbName, String tableName) throws TException {
    return allocateTableWriteIdsBatch(Collections.singletonList(txnId), dbName, tableName, false).get(0).getWriteId();
  }

  @Override
  public List<TxnToWriteId> allocateTableWriteIdsBatch(List<Long> txnIds, String dbName, String tableName)
          throws TException {
    return allocateTableWriteIdsBatch(txnIds, dbName, tableName, false);
  }

  public List<TxnToWriteId> allocateTableWriteIdsBatch(List<Long> txnIds, String dbName, String tableName, boolean shouldRealloc)
          throws TException {
    AllocateTableWriteIdsRequest rqst = new AllocateTableWriteIdsRequest(dbName, tableName);
    rqst.setTxnIds(txnIds);
    rqst.setReallocate(shouldRealloc);
    return allocateTableWriteIdsBatchIntr(rqst);
  }

  @Override
  public List<TxnToWriteId> replAllocateTableWriteIdsBatch(String dbName, String tableName,
                                         String replPolicy, List<TxnToWriteId> srcTxnToWriteIdList) throws TException {
    AllocateTableWriteIdsRequest rqst = new AllocateTableWriteIdsRequest(dbName, tableName);
    rqst.setReplPolicy(replPolicy);
    rqst.setSrcTxnToWriteIdList(srcTxnToWriteIdList);
    return allocateTableWriteIdsBatchIntr(rqst);
  }

  private List<TxnToWriteId> allocateTableWriteIdsBatchIntr(AllocateTableWriteIdsRequest rqst) throws TException {
    return client.allocate_table_write_ids(rqst).getTxnToWriteIds();
  }

  @Override
  public LockResponse lock(LockRequest request) throws TException {
    return client.lock(request);
  }

  @Override
  public LockResponse checkLock(long lockid) throws TException {
    return client.check_lock(new CheckLockRequest(lockid));
  }

  @Override
  public void unlock(long lockid) throws TException {
    client.unlock(new UnlockRequest(lockid));
  }

  @Override
  @Deprecated
  public ShowLocksResponse showLocks() throws TException {
    return client.show_locks(new ShowLocksRequest());
  }

  @Override
  public ShowLocksResponse showLocks(ShowLocksRequest request) throws TException {
    return client.show_locks(request);
  }

  @Override
  public void heartbeat(long txnid, long lockid) throws TException {
    HeartbeatRequest hb = new HeartbeatRequest();
    hb.setLockid(lockid);
    hb.setTxnid(txnid);
    client.heartbeat(hb);
  }

  @Override
  public HeartbeatTxnRangeResponse heartbeatTxnRange(long min, long max) throws TException {
    HeartbeatTxnRangeRequest rqst = new HeartbeatTxnRangeRequest(min, max);
    return client.heartbeat_txn_range(rqst);
  }

  @Override
  @Deprecated
  public void compact(String dbname, String tableName, String partitionName,  CompactionType type)
      throws TException {
    CompactionRequest cr = new CompactionRequest();
    if (dbname == null) {
      cr.setDbname(DEFAULT_DATABASE_NAME);
    } else {
      cr.setDbname(dbname);
    }
    cr.setTablename(tableName);
    if (partitionName != null) {
      cr.setPartitionname(partitionName);
    }
    cr.setType(type);
    client.compact(cr);
  }
  @Deprecated
  @Override
  public void compact(String dbname, String tableName, String partitionName, CompactionType type,
                      Map<String, String> tblproperties) throws TException {
    compact2(dbname, tableName, partitionName, type, tblproperties);
  }

  @Override
  public CompactionResponse compact2(String dbname, String tableName, String partitionName, CompactionType type,
                      Map<String, String> tblproperties) throws TException {
    CompactionRequest cr = new CompactionRequest();
    if (dbname == null) {
      cr.setDbname(DEFAULT_DATABASE_NAME);
    } else {
      cr.setDbname(dbname);
    }
    cr.setTablename(tableName);
    if (partitionName != null) {
      cr.setPartitionname(partitionName);
    }
    cr.setType(type);
    cr.setProperties(tblproperties);
    return client.compact2(cr);
  }

  @Override
  public CompactionResponse compact2(CompactionRequest request) throws TException {
    return client.compact2(request);
  }

  @Override
  public ShowCompactResponse showCompactions() throws TException {
    return client.show_compact(new ShowCompactRequest());
  }

  @Override
  public AbortCompactResponse abortCompactions(AbortCompactionRequest request) throws TException{
    return client.abort_Compactions(request);
  }


  @Override
  public ShowCompactResponse showCompactions(ShowCompactRequest request) throws TException {
    return client.show_compact(request);
  }

  @Override
  public boolean submitForCleanup(CompactionRequest rqst, long highestWriteId,
                                  long txnId) throws TException {
    return client.submit_for_cleanup(rqst, highestWriteId, txnId);
  }

  @Override
  public GetLatestCommittedCompactionInfoResponse getLatestCommittedCompactionInfo(
      GetLatestCommittedCompactionInfoRequest request)
      throws TException {
    return client.get_latest_committed_compaction_info(request);
  }

  @Deprecated
  @Override
  public void addDynamicPartitions(long txnId, long writeId, String dbName, String tableName,
                                   List<String> partNames) throws TException {
    client.add_dynamic_partitions(new AddDynamicPartitions(txnId, writeId, dbName, tableName, partNames));
  }
  @Override
  public void addDynamicPartitions(long txnId, long writeId, String dbName, String tableName,
                                   List<String> partNames, DataOperationType operationType) throws TException {
    AddDynamicPartitions adp = new AddDynamicPartitions(txnId, writeId, dbName, tableName, partNames);
    adp.setOperationType(operationType);
    client.add_dynamic_partitions(adp);
  }

  @Override
  public void insertTable(Table table, boolean overwrite) throws MetaException {
    boolean failed = true;
    HiveMetaHook hook = getHook(table);
    if (hook == null || !(hook instanceof DefaultHiveMetaHook)) {
      return;
    }
    DefaultHiveMetaHook hiveMetaHook = (DefaultHiveMetaHook) hook;
    try {
      hiveMetaHook.commitInsertTable(table, overwrite);
      failed = false;
    }
    finally {
      if (failed) {
        hiveMetaHook.rollbackInsertTable(table, overwrite);
      }
    }
  }

  @Override
  public long getLatestTxnIdInConflict(long txnId) {
    return 0;
  }

  @InterfaceAudience.LimitedPrivate({"HCatalog"})
  @Override
  public NotificationEventResponse getNextNotification(long lastEventId, int maxEvents,
                                                       NotificationFilter filter) throws TException {
    NotificationEventRequest rqst = new NotificationEventRequest(lastEventId);
    rqst.setMaxEvents(maxEvents);
    return getNextNotificationEventsInternal(rqst, false, filter);
  }

  @Override
  public NotificationEventResponse getNextNotification(NotificationEventRequest request,
      boolean allowGapsInEventIds, NotificationFilter filter) throws TException {
    return getNextNotificationEventsInternal(request, allowGapsInEventIds, filter);
  }

  @NotNull
  private NotificationEventResponse getNextNotificationEventsInternal(
      NotificationEventRequest request, boolean allowGapsInEventIds,
      NotificationFilter filter) throws TException {
    long lastEventId = request.getLastEvent();
    NotificationEventResponse rsp = client.get_next_notification(request);
    LOG.debug("Got back " + rsp.getEventsSize() + " events");
    NotificationEventResponse filtered = new NotificationEventResponse();
    if (rsp != null && rsp.getEvents() != null) {
      long nextEventId = lastEventId + 1;
      for (NotificationEvent e : rsp.getEvents()) {
        if (!allowGapsInEventIds && e.getEventId() != nextEventId) {
          LOG.error("Requested events are found missing in NOTIFICATION_LOG table. Expected: {}, Actual: {}. "
                  + "Probably, cleaner would've cleaned it up. "
                  + "Try setting higher value for hive.metastore.event.db.listener.timetolive. "
                  + "Also, bootstrap the system again to get back the consistent replicated state.",
                  nextEventId, e.getEventId());
          throw new IllegalStateException("Notification events are missing.");
        }
        if ((filter != null) && filter.accept(e)) {
          filtered.addToEvents(e);
        }
        nextEventId++;
      }
    }
    return (filter != null) ? filtered : rsp;
  }

  @InterfaceAudience.LimitedPrivate({"HCatalog"})
  @Override
  public CurrentNotificationEventId getCurrentNotificationEventId() throws TException {
    return client.get_current_notificationEventId();
  }

  @InterfaceAudience.LimitedPrivate({"HCatalog"})
  @Override
  public NotificationEventsCountResponse getNotificationEventsCount(NotificationEventsCountRequest rqst)
          throws TException {
    return client.get_notification_events_count(rqst);
  }

  @InterfaceAudience.LimitedPrivate({"Apache Hive, HCatalog"})
  @Override
  public FireEventResponse fireListenerEvent(FireEventRequest rqst) throws TException {
    return client.fire_listener_event(rqst);
  }

  @InterfaceAudience.LimitedPrivate({"Apache Hive, HCatalog"})
  @Override
  public void addWriteNotificationLog(WriteNotificationLogRequest rqst) throws TException {
    client.add_write_notification_log(rqst);
  }

  @InterfaceAudience.LimitedPrivate({"Apache Hive, HCatalog"})
  @Override
  public void addWriteNotificationLogInBatch(WriteNotificationLogBatchRequest rqst) throws TException {
    client.add_write_notification_log_in_batch(rqst);
  }

  /**
   * Creates a synchronized wrapper for any {@link IMetaStoreClient}.
   * This may be used by multi-threaded applications until we have
   * fixed all reentrancy bugs.
   *
   * @param client unsynchronized client
   *
   * @return synchronized client
   */
  public static IMetaStoreClient newSynchronizedClient(
      IMetaStoreClient client) {
    return (IMetaStoreClient) Proxy.newProxyInstance(
      HiveMetaStoreClientPreCatalog.class.getClassLoader(),
      new Class [] { IMetaStoreClient.class },
      new SynchronizedHandler(client));
  }

  private static class SynchronizedHandler implements InvocationHandler {
    private final IMetaStoreClient client;

    SynchronizedHandler(IMetaStoreClient client) {
      this.client = client;
    }

    @Override
    public synchronized Object invoke(Object proxy, Method method, Object [] args)
        throws Throwable {
      try {
        return method.invoke(client, args);
      } catch (InvocationTargetException e) {
        throw e.getTargetException();
      }
    }
  }

  @Override
  public void markPartitionForEvent(String db_name, String tbl_name, Map<String,String> partKVs, PartitionEventType eventType)
      throws TException {
    assert db_name != null;
    assert tbl_name != null;
    assert partKVs != null;
    client.markPartitionForEvent(db_name, tbl_name, partKVs, eventType);
  }

  @Override
  public boolean isPartitionMarkedForEvent(String db_name, String tbl_name, Map<String,String> partKVs, PartitionEventType eventType)
      throws TException {
    assert db_name != null;
    assert tbl_name != null;
    assert partKVs != null;
    return client.isPartitionMarkedForEvent(db_name, tbl_name, partKVs, eventType);
  }

  @Override
  public void createFunction(Function func) throws TException {
    client.create_function(func);
  }

  @Override
  public void alterFunction(String dbName, String funcName, Function newFunction) throws TException {
    client.alter_function(dbName, funcName, newFunction);
  }

  @Override
  public void dropFunction(String dbName, String funcName) throws TException {
    client.drop_function(dbName, funcName);
  }

  @Override
  public Function getFunction(String dbName, String funcName) throws TException {
    Function f = client.get_function(dbName, funcName);
    return fastpath ? f : deepCopy(f);
  }

  @Override
  public List<String> getFunctions(String dbName, String pattern) throws TException {
    return client.get_functions(dbName, pattern);
  }

  @Override
  public GetAllFunctionsResponse getAllFunctions() throws TException {
    return client.get_all_functions();
  }

  protected void create_table_with_environment_context(Table tbl, EnvironmentContext envContext)
      throws TException {
    client.create_table_with_environment_context(tbl, envContext);
  }

  protected void drop_table_with_environment_context(String dbname, String name,
      boolean deleteData, EnvironmentContext envContext) throws TException, UnsupportedOperationException {
    client.drop_table_with_environment_context(dbname, name, deleteData, envContext);
  }

  @Override
  public AggrStats getAggrColStatsFor(String dbName, String tblName,
    List<String> colNames, List<String> partNames, String engine) throws TException {
    if (colNames.isEmpty() || partNames.isEmpty()) {
      LOG.debug("Columns is empty or partNames is empty : Short-circuiting stats eval on client side.");
      return new AggrStats(new ArrayList<>(),0); // Nothing to aggregate
    }
    PartitionsStatsRequest req = new PartitionsStatsRequest(dbName, tblName, colNames, partNames);
    req.setEngine(engine);
    return client.get_aggr_stats_for(req);
  }

  @Override
  public AggrStats getAggrColStatsFor(
      String dbName, String tblName, List<String> colNames,
      List<String> partName, String engine, String writeIdList)
      throws TException {
    if (colNames.isEmpty() || partName.isEmpty()) {
      LOG.debug("Columns is empty or partNames is empty : Short-circuiting stats eval on client side.");
      return new AggrStats(new ArrayList<>(),0); // Nothing to aggregate
    }
    PartitionsStatsRequest req = new PartitionsStatsRequest(dbName, tblName, colNames, partName);
    req.setEngine(engine);
    req.setValidWriteIdList(writeIdList);
    return client.get_aggr_stats_for(req);
  }

  @Override
  public Iterable<Entry<Long, ByteBuffer>> getFileMetadata(
      final List<Long> fileIds) throws TException {
    return new MetastoreMapIterable<Long, ByteBuffer>() {
      private int listIndex = 0;
      @Override
      protected Map<Long, ByteBuffer> fetchNextBatch() throws TException {
        if (listIndex == fileIds.size()) {
          return null;
        }
        int endIndex = Math.min(listIndex + fileMetadataBatchSize, fileIds.size());
        List<Long> subList = fileIds.subList(listIndex, endIndex);
        GetFileMetadataResult resp = sendGetFileMetadataReq(subList);
        // TODO: we could remember if it's unsupported and stop sending calls; although, it might
        //       be a bad idea for HS2+standalone metastore that could be updated with support.
        //       Maybe we should just remember this for some time.
        if (!resp.isIsSupported()) {
          return null;
        }
        listIndex = endIndex;
        return resp.getMetadata();
      }
    };
  }

  private GetFileMetadataResult sendGetFileMetadataReq(List<Long> fileIds) throws TException {
    return client.get_file_metadata(new GetFileMetadataRequest(fileIds));
  }

  @Override
  public Iterable<Entry<Long, MetadataPpdResult>> getFileMetadataBySarg(
      final List<Long> fileIds, final ByteBuffer sarg, final boolean doGetFooters) {
    return new MetastoreMapIterable<Long, MetadataPpdResult>() {
      private int listIndex = 0;
      @Override
      protected Map<Long, MetadataPpdResult> fetchNextBatch() throws TException {
        if (listIndex == fileIds.size()) {
          return null;
        }
        int endIndex = Math.min(listIndex + fileMetadataBatchSize, fileIds.size());
        List<Long> subList = fileIds.subList(listIndex, endIndex);
        GetFileMetadataByExprResult resp = sendGetFileMetadataBySargReq(
            sarg, subList, doGetFooters);
        if (!resp.isIsSupported()) {
          return null;
        }
        listIndex = endIndex;
        return resp.getMetadata();
      }
    };
  }

  private GetFileMetadataByExprResult sendGetFileMetadataBySargReq(
      ByteBuffer sarg, List<Long> fileIds, boolean doGetFooters) throws TException {
    GetFileMetadataByExprRequest req = new GetFileMetadataByExprRequest(fileIds, sarg);
    req.setDoGetFooters(doGetFooters); // No need to get footers
    return client.get_file_metadata_by_expr(req);
  }

  public static abstract class MetastoreMapIterable<K, V>
    implements Iterable<Entry<K, V>>, Iterator<Entry<K, V>> {
    private Iterator<Entry<K, V>> currentIter;

    protected abstract Map<K, V> fetchNextBatch() throws TException;

    @Override
    public Iterator<Entry<K, V>> iterator() {
      return this;
    }

    @Override
    public boolean hasNext() {
      ensureCurrentBatch();
      return currentIter != null;
    }

    private void ensureCurrentBatch() {
      if (currentIter != null && currentIter.hasNext()) {
        return;
      }
      currentIter = null;
      Map<K, V> currentBatch;
      do {
        try {
          currentBatch = fetchNextBatch();
        } catch (TException ex) {
          throw new RuntimeException(ex);
        }
        if (currentBatch == null)
         {
          return; // No more data.
        }
      } while (currentBatch.isEmpty());
      currentIter = currentBatch.entrySet().iterator();
    }

    @Override
    public Entry<K, V> next() {
      ensureCurrentBatch();
      if (currentIter == null) {
        throw new NoSuchElementException();
      }
      return currentIter.next();
    }

    @Override
    public void remove() {
      throw new UnsupportedOperationException();
    }
  }

  @Override
  public void clearFileMetadata(List<Long> fileIds) throws TException {
    ClearFileMetadataRequest req = new ClearFileMetadataRequest();
    req.setFileIds(fileIds);
    client.clear_file_metadata(req);
  }

  @Override
  public void putFileMetadata(List<Long> fileIds, List<ByteBuffer> metadata) throws TException {
    PutFileMetadataRequest req = new PutFileMetadataRequest();
    req.setFileIds(fileIds);
    req.setMetadata(metadata);
    client.put_file_metadata(req);
  }

  @Override
  public boolean isSameConfObj(Configuration c) {
    return conf == c;
  }

  @Override
  public boolean cacheFileMetadata(
      String dbName, String tableName, String partName, boolean allParts) throws TException {
    CacheFileMetadataRequest req = new CacheFileMetadataRequest();
    req.setDbName(dbName);
    req.setTblName(tableName);
    if (partName != null) {
      req.setPartName(partName);
    } else {
      req.setIsAllParts(allParts);
    }
    CacheFileMetadataResult result = client.cache_file_metadata(req);
    return result.isIsSupported();
  }

  @Override
  public String getMetastoreDbUuid() throws TException {
    return client.get_metastore_db_uuid();
  }

  @Override
  public void createResourcePlan(WMResourcePlan resourcePlan, String copyFromName) throws TException {
    WMCreateResourcePlanRequest request = new WMCreateResourcePlanRequest();
    request.setResourcePlan(resourcePlan);
    request.setCopyFrom(copyFromName);
    client.create_resource_plan(request);
  }

  @Override
  public WMFullResourcePlan getResourcePlan(String resourcePlanName, String ns) throws TException {
    WMGetResourcePlanRequest request = new WMGetResourcePlanRequest();
    request.setResourcePlanName(resourcePlanName);
    request.setNs(ns);
    return client.get_resource_plan(request).getResourcePlan();
  }

  @Override
  public List<WMResourcePlan> getAllResourcePlans(String ns) throws TException {
    WMGetAllResourcePlanRequest request = new WMGetAllResourcePlanRequest();
    request.setNs(ns);
    return client.get_all_resource_plans(request).getResourcePlans();
  }

  @Override
  public void dropResourcePlan(String resourcePlanName, String ns) throws TException {
    WMDropResourcePlanRequest request = new WMDropResourcePlanRequest();
    request.setResourcePlanName(resourcePlanName);
    request.setNs(ns);
    client.drop_resource_plan(request);
  }

  @Override
  public WMFullResourcePlan alterResourcePlan(String resourcePlanName, String ns,
      WMNullableResourcePlan resourcePlan,
      boolean canActivateDisabled, boolean isForceDeactivate, boolean isReplace)
      throws TException {
    WMAlterResourcePlanRequest request = new WMAlterResourcePlanRequest();
    request.setResourcePlanName(resourcePlanName);
    request.setNs(ns);
    request.setResourcePlan(resourcePlan);
    request.setIsEnableAndActivate(canActivateDisabled);
    request.setIsForceDeactivate(isForceDeactivate);
    request.setIsReplace(isReplace);
    WMAlterResourcePlanResponse resp = client.alter_resource_plan(request);
    return resp.isSetFullResourcePlan() ? resp.getFullResourcePlan() : null;
  }

  @Override
  public WMFullResourcePlan getActiveResourcePlan(String ns) throws TException {
    WMGetActiveResourcePlanRequest request = new WMGetActiveResourcePlanRequest();
    request.setNs(ns);
    return client.get_active_resource_plan(request).getResourcePlan();
  }

  @Override
  public WMValidateResourcePlanResponse validateResourcePlan(String resourcePlanName, String ns)
      throws TException {
    WMValidateResourcePlanRequest request = new WMValidateResourcePlanRequest();
    request.setResourcePlanName(resourcePlanName);
    request.setNs(ns);
    return client.validate_resource_plan(request);
  }

  @Override
  public void createWMTrigger(WMTrigger trigger) throws TException {
    WMCreateTriggerRequest request = new WMCreateTriggerRequest();
    request.setTrigger(trigger);
    client.create_wm_trigger(request);
  }

  @Override
  public void alterWMTrigger(WMTrigger trigger) throws TException {
    WMAlterTriggerRequest request = new WMAlterTriggerRequest();
    request.setTrigger(trigger);
    client.alter_wm_trigger(request);
  }

  @Override
  public void dropWMTrigger(String resourcePlanName, String triggerName, String ns) throws TException {
    WMDropTriggerRequest request = new WMDropTriggerRequest();
    request.setResourcePlanName(resourcePlanName);
    request.setTriggerName(triggerName);
    request.setNs(ns);
    client.drop_wm_trigger(request);
  }

  @Override
  public List<WMTrigger> getTriggersForResourcePlan(String resourcePlan, String ns) throws TException {
    WMGetTriggersForResourePlanRequest request = new WMGetTriggersForResourePlanRequest();
    request.setResourcePlanName(resourcePlan);
    request.setNs(ns);
    return client.get_triggers_for_resourceplan(request).getTriggers();
  }

  @Override
  public void createWMPool(WMPool pool) throws TException {
    WMCreatePoolRequest request = new WMCreatePoolRequest();
    request.setPool(pool);
    client.create_wm_pool(request);
  }

  @Override
  public void alterWMPool(WMNullablePool pool, String poolPath) throws TException {
    WMAlterPoolRequest request = new WMAlterPoolRequest();
    request.setPool(pool);
    request.setPoolPath(poolPath);
    client.alter_wm_pool(request);
  }

  @Override
  public void dropWMPool(String resourcePlanName, String poolPath, String ns) throws TException {
    WMDropPoolRequest request = new WMDropPoolRequest();
    request.setResourcePlanName(resourcePlanName);
    request.setPoolPath(poolPath);
    request.setNs(ns);
    client.drop_wm_pool(request);
  }

  @Override
  public void createOrUpdateWMMapping(WMMapping mapping, boolean isUpdate) throws TException {
    WMCreateOrUpdateMappingRequest request = new WMCreateOrUpdateMappingRequest();
    request.setMapping(mapping);
    request.setUpdate(isUpdate);
    client.create_or_update_wm_mapping(request);
  }

  @Override
  public void dropWMMapping(WMMapping mapping) throws TException {
    WMDropMappingRequest request = new WMDropMappingRequest();
    request.setMapping(mapping);
    client.drop_wm_mapping(request);
  }

  @Override
  public void createOrDropTriggerToPoolMapping(String resourcePlanName, String triggerName,
      String poolPath, boolean shouldDrop, String ns) throws TException {
    WMCreateOrDropTriggerToPoolMappingRequest request = new WMCreateOrDropTriggerToPoolMappingRequest();
    request.setResourcePlanName(resourcePlanName);
    request.setTriggerName(triggerName);
    request.setPoolPath(poolPath);
    request.setDrop(shouldDrop);
    request.setNs(ns);
    client.create_or_drop_wm_trigger_to_pool_mapping(request);
  }


  @Override
  public void createCatalog(Catalog catalog) throws TException {
    throw new UnsupportedOperationException();
  }

  @Override
  public Catalog getCatalog(String catName) throws TException {
    throw new UnsupportedOperationException();
  }

  @Override
  public void alterCatalog(String catalogName, Catalog newCatalog) {
    throw new UnsupportedOperationException();
  }

  @Override
  public List<String> getCatalogs() throws TException {
    throw new UnsupportedOperationException();
  }

  @Override
  public void dropCatalog(String catName) throws TException {
    throw new UnsupportedOperationException();
  }

  @Override
  public void dropCatalog(String catName, boolean ifExists) throws TException {
    throw new UnsupportedOperationException();
  }

  @Override
  public List<String> getDatabases(String catName, String databasePattern) throws TException {
    throw new UnsupportedOperationException();
  }

  @Override
  public List<String> getAllDatabases(String catName) throws TException {
    throw new UnsupportedOperationException();
  }

  @Override
  public GetDatabaseObjectsResponse get_databases_req(GetDatabaseObjectsRequest request) throws TException {
    throw new UnsupportedOperationException();
  }

  @Override
  public List<String> getTables(String catName, String dbName, String tablePattern) throws
          TException {
    throw new UnsupportedOperationException();
  }

  @Override
  public List<String> getTables(String catName, String dbName, String tablePattern,
                                TableType tableType) throws TException {
    throw new UnsupportedOperationException();
  }

  @Override
  public List<String> getMaterializedViewsForRewriting(String catName, String dbName) {
    throw new UnsupportedOperationException();
  }

  @Override
  public List<TableMeta> getTableMeta(String catName, String dbPatterns, String tablePatterns,
                                      List<String> tableTypes) throws TException {
    throw new UnsupportedOperationException();
  }

  @Override
  public List<String> getAllTables(String catName, String dbName) throws TException {
    throw new UnsupportedOperationException();
  }

  @Override
  public List<String> listTableNamesByFilter(String catName, String dbName, String filter,
                                             int maxTables) throws TException {
    throw new UnsupportedOperationException();
  }

  @Override
  public void dropTable(String catName, String dbName, String tableName, boolean deleteData,
                        boolean ignoreUnknownTable, boolean ifPurge) throws TException {
    throw new UnsupportedOperationException();
  }

  @Override
  public void truncateTable(String catName, String dbName, String tableName,
                            List<String> partNames) throws TException {
    throw new UnsupportedOperationException();
  }

  @Override
  public boolean tableExists(String catName, String dbName, String tableName) throws TException {
    throw new UnsupportedOperationException();
  }

  @Override
  public Database getDatabase(String catalogName, String databaseName) throws
          TException {
    throw new UnsupportedOperationException();
  }

  @Override
  public Table getTable(String catName, String dbName, String tableName) throws TException {
    throw new UnsupportedOperationException();
  }

  @Override
  public Table getTable(String catName, String dbName, boolean getColumnStats, String engine) throws
          TException {
    throw new UnsupportedOperationException();
  }

  @Override
  public Table getTable(String catName, String dbName, String tableName,
                        String validWriteIdList) throws TException {
    throw new UnsupportedOperationException();
  }

  @Override
  public Table getTable(String catName, String dbName, String tableName,
                        String validWriteIdList, boolean getColumnStats, String engine) throws TException {
    throw new UnsupportedOperationException();
  }

  @Override
  public Table getTable(GetTableRequest getTableRequest) throws TException {
    throw new UnsupportedOperationException();
  }

  @Override
  public List<Table> getTableObjectsByName(String catName, String dbName,
                                           List<String> tableNames) throws TException {
    throw new UnsupportedOperationException();
  }

  @Override
  public List<Table> getTables(String catName, String dbName, List<String> tableNames,
                                           GetProjectionsSpec projectionsSpec) throws TException {
    throw new UnsupportedOperationException();
  }

  @Override
  public void updateCreationMetadata(String catName, String dbName, String tableName,
                                     CreationMetadata cm) throws TException {
    throw new UnsupportedOperationException();
  }

  @Override
  public Partition appendPartition(String catName, String dbName, String tableName,
                                   List<String> partVals) throws TException {
    throw new UnsupportedOperationException();
  }

  @Override
  public Partition appendPartition(String catName, String dbName, String tableName,
                                   String name) throws TException {
    throw new UnsupportedOperationException();
  }

  @Override
  public Partition getPartition(String catName, String dbName, String tblName,
                                List<String> partVals) throws TException {
    throw new UnsupportedOperationException();
  }

  @Override
  public Partition exchange_partition(Map<String, String> partitionSpecs, String sourceCat,
                                      String sourceDb, String sourceTable, String destCat,
                                      String destdb, String destTableName) {
    throw new UnsupportedOperationException();
  }

  @Override
  public List<Partition> exchange_partitions(Map<String, String> partitionSpecs, String sourceCat,
                                             String sourceDb, String sourceTable, String destCat,
                                             String destdb, String destTableName) throws TException {
    throw new UnsupportedOperationException();
  }

  @Override
  public Partition getPartition(String catName, String dbName, String tblName, String name) throws
          TException {
    throw new UnsupportedOperationException();
  }

  @Override
  public Partition getPartitionWithAuthInfo(String catName, String dbName, String tableName,
                                            List<String> pvals, String userName,
                                            List<String> groupNames) throws TException {
    throw new UnsupportedOperationException();
  }

  @Override
  public List<Partition> listPartitions(String catName, String db_name, String tbl_name,
                                        int max_parts) throws TException {
    throw new UnsupportedOperationException();
  }

  @Override
  public PartitionSpecProxy listPartitionSpecs(String catName, String dbName, String tableName,
                                               int maxParts) {
    throw new UnsupportedOperationException();
  }

  @Override
  public List<Partition> listPartitions(String catName, String db_name, String tbl_name,
                                        List<String> part_vals, int max_parts) throws TException {
    throw new UnsupportedOperationException();
  }

  @Override
  public List<String> listPartitionNames(String catName, String db_name, String tbl_name,
                                         int max_parts) throws TException {
    throw new UnsupportedOperationException();
  }

  @Override
  public List<String> listPartitionNames(String catName, String db_name, String tbl_name,
                                         List<String> part_vals, int max_parts) throws TException {
    throw new UnsupportedOperationException();
  }

  @Override
  public int getNumPartitionsByFilter(String catName, String dbName, String tableName,
                                      String filter) throws TException {
    throw new UnsupportedOperationException();
  }

  @Override
  public List<Partition> listPartitionsByFilter(String catName, String db_name, String tbl_name,
                                                String filter, int max_parts) throws TException {
    throw new UnsupportedOperationException();
  }

  @Override
  public PartitionSpecProxy listPartitionSpecsByFilter(String catName, String db_name,
                                                       String tbl_name, String filter,
                                                       int max_parts) {
    throw new UnsupportedOperationException();
  }

  @Override
  public boolean listPartitionsByExpr(String catName, String db_name, String tbl_name, byte[] expr,
                                      String default_partition_name, int max_parts,
                                      List<Partition> result) {
    throw new UnsupportedOperationException();
  }

  @Override
  public List<Partition> listPartitionsWithAuthInfo(String catName, String dbName, String tableName,
                                                    int maxParts, String userName,
                                                    List<String> groupNames) throws TException {
    throw new UnsupportedOperationException();
  }

  @Override
  public List<Partition> getPartitionsByNames(String catName, String db_name, String tbl_name,
                                              List<String> part_names) throws TException {
    throw new UnsupportedOperationException();
  }

  @Override public GetPartitionsByNamesResult getPartitionsByNames(GetPartitionsByNamesRequest req) throws TException {
    throw new UnsupportedOperationException();
  }

  @Override
  public List<Partition> listPartitionsWithAuthInfo(String catName, String dbName, String tableName,
                                                    List<String> partialPvals, int maxParts,
                                                    String userName, List<String> groupNames) throws TException {
    throw new UnsupportedOperationException();
  }

  @Override
  public void markPartitionForEvent(String catName, String db_name, String tbl_name,
                                    Map<String, String> partKVs,
                                    PartitionEventType eventType) throws TException {
    throw new UnsupportedOperationException();
  }

  @Override
  public boolean isPartitionMarkedForEvent(String catName, String db_name, String tbl_name,
                                           Map<String, String> partKVs,
                                           PartitionEventType eventType) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void alter_table(String catName, String dbName, String tblName, Table newTable,
                          EnvironmentContext envContext) throws TException {
    throw new UnsupportedOperationException();
  }

  @Override
  public void dropDatabase(DropDatabaseRequest req) throws TException {
    throw new UnsupportedOperationException();
  }

  @Override
  public void alterDatabase(String catName, String dbName, Database newDb) throws TException {
    throw new UnsupportedOperationException();
  }

  @Override
  public boolean dropPartition(String catName, String db_name, String tbl_name,
                               List<String> part_vals, boolean deleteData) throws TException {
    throw new UnsupportedOperationException();
  }

  @Override
  public boolean dropPartition(String catName, String db_name, String tbl_name,
                               List<String> part_vals, PartitionDropOptions options) throws TException {
    throw new UnsupportedOperationException();
  }

  @Override
  public List<Partition> dropPartitions(String catName, String dbName, String tblName,
                                        List<Pair<Integer, byte[]>> partExprs,
                                        PartitionDropOptions options) throws TException {
    throw new UnsupportedOperationException();
  }

  @Override
  public boolean dropPartition(String catName, String db_name, String tbl_name, String name,
                               boolean deleteData) throws TException {
    throw new UnsupportedOperationException();
  }

  @Override
  public void alter_partition(String catName, String dbName, String tblName, Partition newPart,
                              EnvironmentContext environmentContext) throws TException {
    throw new UnsupportedOperationException();
  }

  @Override
  public void alter_partitions(String catName, String dbName, String tblName,
                               List<Partition> newParts,
                               EnvironmentContext environmentContext,
                               String writeIdList, long writeId) throws TException {
    throw new UnsupportedOperationException();
  }

  @Override
  public void renamePartition(String catName, String dbname, String tableName,
      List<String> part_vals, Partition newPart, String validWriteIds, long txnId, boolean makeCopy)
          throws TException {
    throw new UnsupportedOperationException();
  }

  @Override
  public List<FieldSchema> getFields(String catName, String db, String tableName) throws TException {
    throw new UnsupportedOperationException();
  }

  @Override
  public GetFieldsResponse getFieldsRequest(GetFieldsRequest req) {
    throw new UnsupportedOperationException("getFieldsRequest is not supported in HiveMetastoreClientPreCatalog. "
        + "Use HiveMetastoreClient instead");
  }

  @Override
  public List<FieldSchema> getSchema(String catName, String db, String tableName) throws TException {
    throw new UnsupportedOperationException();
  }

  @Override
  public GetSchemaResponse getSchemaRequest(GetSchemaRequest req) {
    throw new UnsupportedOperationException("getSchemaRequest is not supported in HiveMetastoreClientPreCatalog. "
        + "Use HiveMetastoreClient instead");
  }

  @Override
  public List<ColumnStatisticsObj> getTableColumnStatistics(String catName, String dbName,
                                                            String tableName,
                                                            List<String> colNames,
                                                            String engine) throws TException {
    throw new UnsupportedOperationException();
  }

  @Override
  public List<ColumnStatisticsObj> getTableColumnStatistics(
      String catName, String dbName, String tableName, List<String> colNames,
      String engine, String validWriteIdList)
      throws TException {
    throw new UnsupportedOperationException();
  }

  @Override
  public Map<String, List<ColumnStatisticsObj>> getPartitionColumnStatistics(String catName,
                                                                             String dbName,
                                                                             String tableName,
                                                                             List<String> partNames,
                                                                             List<String> colNames,
                                                                             String engine) throws TException {
    throw new UnsupportedOperationException();
  }

  @Override
  public Map<String, List<ColumnStatisticsObj>> getPartitionColumnStatistics(
      String catName, String dbName, String tableName, List<String> partNames,
      List<String> colNames, String engine, String validWriteIdList)
      throws TException {
    throw new UnsupportedOperationException();
  }

  @Override
  public void alterFunction(String catName, String dbName, String funcName,
                            Function newFunction) throws TException {
    throw new UnsupportedOperationException();
  }

  @Override
  public void dropFunction(String catName, String dbName, String funcName) throws TException {
    throw new UnsupportedOperationException();
  }

  @Override
  public Function getFunction(String catName, String dbName, String funcName) throws TException {
    throw new UnsupportedOperationException();
  }

  @Override
  public List<String> getFunctions(String catName, String dbName, String pattern) throws TException {
    throw new UnsupportedOperationException();
  }

  @Override
  public GetFunctionsResponse getFunctionsRequest(GetFunctionsRequest functionRequest) throws TException {
    throw new UnsupportedOperationException();
  }

  @Override
  public AggrStats getAggrColStatsFor(String catName, String dbName, String tblName,
      List<String> colNames, List<String> partNames, String engine) throws TException {
    throw new UnsupportedOperationException();
  }

  @Override
  public AggrStats getAggrColStatsFor(String catName, String dbName, String tblName,
      List<String> colNames, List<String> partNames, String engine, String writeIdList)
      throws TException {
    throw new UnsupportedOperationException();
  }

  @Override
  public void dropConstraint(String catName, String dbName, String tableName,
                             String constraintName) throws TException {
    throw new UnsupportedOperationException();
  }

  @Override
  public void createISchema(ISchema schema) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void alterISchema(String catName, String dbName, String schemaName,
                           ISchema newSchema) {
    throw new UnsupportedOperationException();
  }

  @Override
  public ISchema getISchema(String catName, String dbName, String name) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void dropISchema(String catName, String dbName, String name) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void addSchemaVersion(SchemaVersion schemaVersion) {
    throw new UnsupportedOperationException();
  }

  @Override
  public SchemaVersion getSchemaVersion(String catName, String dbName, String schemaName,
                                        int version) {
    throw new UnsupportedOperationException();
  }

  @Override
  public SchemaVersion getSchemaLatestVersion(String catName, String dbName,
                                              String schemaName) {
    throw new UnsupportedOperationException();
  }

  @Override
  public List<SchemaVersion> getSchemaAllVersions(String catName, String dbName,
                                                  String schemaName) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void dropSchemaVersion(String catName, String dbName, String schemaName, int version) {
    throw new UnsupportedOperationException();
  }

  @Override
  public FindSchemasByColsResp getSchemaByCols(FindSchemasByColsRqst rqst) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void mapSchemaVersionToSerde(String catName, String dbName, String schemaName, int version,
                                      String serdeName) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void setSchemaVersionState(String catName, String dbName, String schemaName, int version,
                                    SchemaVersionState state) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void addSerDe(SerDeInfo serDeInfo) {
    throw new UnsupportedOperationException();
  }

  @Override
  public SerDeInfo getSerDe(String serDeName) throws TException {
    throw new UnsupportedOperationException();
  }

  /** {@inheritDoc} */
  @Override
  public LockResponse lockMaterializationRebuild(String dbName, String tableName, long txnId) {
    throw new UnsupportedOperationException();
  }

  /** {@inheritDoc} */
  @Override
  public boolean heartbeatLockMaterializationRebuild(String dbName, String tableName, long txnId) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void addRuntimeStat(RuntimeStat stat) {
    throw new UnsupportedOperationException();
  }

  @Override
  public List<RuntimeStat> getRuntimeStats(int maxWeight, int maxCreateTime) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void alter_table(String catName, String databaseName, String tblName, Table table,
      EnvironmentContext environmentContext, String validWriteIdList)
      throws TException {
    throw new UnsupportedOperationException();
  }

  @Override
  public void alter_partition(String catName, String dbName, String tblName, Partition newPart,
      EnvironmentContext environmentContext, String writeIdList) throws TException {
    throw new UnsupportedOperationException();
  }

  @Override
  public void truncateTable(String dbName, String tableName,
      List<String> partNames, String validWriteIds, long writeId)
      throws TException {
    throw new UnsupportedOperationException();
  }

  @Override
  public void truncateTable(TableName table, List<String> partNames) throws MetaException, TException {
    throw new UnsupportedOperationException();
  }

  @Override
  public void truncateTable(String dbName, String tableName, 
      List<String> partNames, String validWriteIds, long writeId, boolean deleteData) 
      throws TException {
    throw new UnsupportedOperationException();
  }

  @Override
  public GetPartitionsResponse getPartitionsWithSpecs(GetPartitionsRequest request) {
    throw new UnsupportedOperationException();
  }

  @Override
  @Deprecated
  public OptionalCompactionInfoStruct findNextCompact(String workerId) throws TException {
    return client.find_next_compact(workerId);
  }

  @Override
  public OptionalCompactionInfoStruct findNextCompact(FindNextCompactRequest rqst) throws TException {
    return client.find_next_compact2(rqst);
  }

  @Override
  public void updateCompactorState(CompactionInfoStruct cr, long txnId) throws TException {
    client.update_compactor_state(cr, txnId);
  }

  @Override
  public List<String> findColumnsWithStats(CompactionInfoStruct cr) throws TException {
    return client.find_columns_with_stats(cr);
  }

  @Override
  public void markCleaned(CompactionInfoStruct cr) throws TException {
    client.mark_cleaned(cr);
  }

  @Override
  public void markCompacted(CompactionInfoStruct cr) throws TException {
    client.mark_compacted(cr);
  }

  @Override
  public void markFailed(CompactionInfoStruct cr) throws TException {
    client.mark_failed(cr);
  }

  @Override
  public void markRefused(CompactionInfoStruct cr) throws TException {
    client.mark_refused(cr);
  }

  @Override
  public boolean updateCompactionMetricsData(CompactionMetricsDataStruct struct)
      throws TException {
    return client.update_compaction_metrics_data(struct);
  }

  @Override
  public void removeCompactionMetricsData(CompactionMetricsDataRequest request) throws TException {
    client.remove_compaction_metrics_data(request);
  }

  @Override
  public void setHadoopJobid(String jobId, long cqId) throws TException {
    client.set_hadoop_jobid(jobId, cqId);
  }

  @Override
  public String getServerVersion() throws TException {
    return client.getVersion();
  }

  @Override
  public ScheduledQuery getScheduledQuery(ScheduledQueryKey key) throws TException {
    return client.get_scheduled_query(key);
  }

  @Override
  public void scheduledQueryProgress(ScheduledQueryProgressInfo info) throws TException {
    client.scheduled_query_progress(info);
  }

  @Override
  public void addReplicationMetrics(ReplicationMetricList replicationMetricList) throws TException {
    client.add_replication_metrics(replicationMetricList);
  }

  @Override
  public ReplicationMetricList getReplicationMetrics(GetReplicationMetricsRequest
                                                         replicationMetricsRequest) throws TException {
    return client.get_replication_metrics(replicationMetricsRequest);
  }

  @Override
  public void createStoredProcedure(StoredProcedure proc) throws TException {
    client.create_stored_procedure(proc);
  }

  @Override
  public StoredProcedure getStoredProcedure(StoredProcedureRequest request) throws TException {
    return client.get_stored_procedure(request);
  }

  @Override
  public void dropStoredProcedure(StoredProcedureRequest request) throws TException {
    client.drop_stored_procedure(request);
  }

  @Override
  public List<String> getAllStoredProcedures(ListStoredProcedureRequest request) throws TException {
    return client.get_all_stored_procedures(request);
  }

  @Override
  public void addPackage(AddPackageRequest request) throws TException {
    client.add_package(request);
  }

  @Override
  public Package findPackage(GetPackageRequest request) throws TException {
    return client.find_package(request);
  }

  @Override
  public List<String> listPackages(ListPackageRequest request) throws TException {
    return client.get_all_packages(request);
  }

  @Override
  public void dropPackage(DropPackageRequest request) throws TException {
    client.drop_package(request);
  }

  @Override
  public List<WriteEventInfo> getAllWriteEventInfo(GetAllWriteEventInfoRequest request)
      throws TException {
    return client.get_all_write_event_info(request);
  }

  @Override
  public ScheduledQueryPollResponse scheduledQueryPoll(ScheduledQueryPollRequest request)
      throws TException {
    return client.scheduled_query_poll(request);
  }

  @Override
  public void scheduledQueryMaintenance(ScheduledQueryMaintenanceRequest request) throws TException {
    client.scheduled_query_maintenance(request);
  }

  @Override
  public long getMaxAllocatedWriteId(String dbName, String tableName) {
    throw new NotImplementedException("");
  }

  @Override
  public void seedWriteId(String dbName, String tableName, long seedWriteId) {
    throw new NotImplementedException("");
  }

  @Override
  public void seedTxnId(long seedTxnId) {
    throw new NotImplementedException("");
  }
}
