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

package org.apache.tajo.pullserver;

import com.google.common.collect.Lists;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.TooLongFrameException;
import io.netty.handler.codec.http.*;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.stream.ChunkedWriteHandler;
import io.netty.util.CharsetUtil;
import io.netty.util.concurrent.GenericFutureListener;
import io.netty.util.concurrent.GlobalEventExecutor;

import org.apache.commons.codec.binary.Base64;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.LocalDirAllocator;
import org.apache.hadoop.fs.LocalFileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.DataInputByteBuffer;
import org.apache.hadoop.io.DataOutputBuffer;
import org.apache.hadoop.io.ReadaheadPool;
import org.apache.hadoop.metrics2.MetricsSystem;
import org.apache.hadoop.metrics2.annotation.Metric;
import org.apache.hadoop.metrics2.annotation.Metrics;
import org.apache.hadoop.metrics2.lib.DefaultMetricsSystem;
import org.apache.hadoop.metrics2.lib.MutableCounterInt;
import org.apache.hadoop.metrics2.lib.MutableCounterLong;
import org.apache.hadoop.metrics2.lib.MutableGaugeInt;
import org.apache.hadoop.security.ssl.SSLFactory;
import org.apache.hadoop.yarn.api.records.ApplicationId;
import org.apache.hadoop.yarn.server.api.ApplicationInitializationContext;
import org.apache.hadoop.yarn.server.api.ApplicationTerminationContext;
import org.apache.hadoop.yarn.server.api.AuxiliaryService;
import org.apache.tajo.QueryId;
import org.apache.tajo.catalog.Schema;
import org.apache.tajo.conf.TajoConf;
import org.apache.tajo.conf.TajoConf.ConfVars;
import org.apache.tajo.pullserver.retriever.FileChunk;
import org.apache.tajo.rpc.RpcChannelFactory;
import org.apache.tajo.storage.RowStoreUtil;
import org.apache.tajo.storage.RowStoreUtil.RowStoreDecoder;
import org.apache.tajo.storage.Tuple;
import org.apache.tajo.storage.TupleComparator;
import org.apache.tajo.storage.index.bst.BSTIndex;
import org.apache.tajo.util.TajoIdUtils;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class PullServerAuxService extends AuxiliaryService {

  private static final Log LOG = LogFactory.getLog(PullServerAuxService.class);
  
  public static final String SHUFFLE_MANAGE_OS_CACHE = "tajo.pullserver.manage.os.cache";
  public static final boolean DEFAULT_SHUFFLE_MANAGE_OS_CACHE = true;

  public static final String SHUFFLE_READAHEAD_BYTES = "tajo.pullserver.readahead.bytes";
  public static final int DEFAULT_SHUFFLE_READAHEAD_BYTES = 4 * 1024 * 1024;

  private int port;
  private ServerBootstrap selector;
  private final ChannelGroup accepted = new DefaultChannelGroup(GlobalEventExecutor.INSTANCE);
  private HttpChannelInitializer initializer;
  private int sslFileBufferSize;

  private ApplicationId appId;
  private QueryId queryId;
  private FileSystem localFS;

  /**
   * Should the shuffle use posix_fadvise calls to manage the OS cache during
   * sendfile
   */
  private boolean manageOsCache;
  private int readaheadLength;
  private ReadaheadPool readaheadPool = ReadaheadPool.getInstance();
   

  public static final String PULLSERVER_SERVICEID = "tajo.pullserver";

  private static final Map<String,String> userRsrc =
    new ConcurrentHashMap<String,String>();
  private static String userName;

  public static final String SUFFLE_SSL_FILE_BUFFER_SIZE_KEY =
    "tajo.pullserver.ssl.file.buffer.size";

  public static final int DEFAULT_SUFFLE_SSL_FILE_BUFFER_SIZE = 60 * 1024;

  @Metrics(name="PullServerShuffleMetrics", about="PullServer output metrics", context="tajo")
  static class ShuffleMetrics implements GenericFutureListener<ChannelFuture> {
    @Metric({"OutputBytes","PullServer output in bytes"})
    MutableCounterLong shuffleOutputBytes;
    @Metric({"Failed","# of failed shuffle outputs"})
    MutableCounterInt shuffleOutputsFailed;
    @Metric({"Succeeded","# of succeeded shuffle outputs"})
    MutableCounterInt shuffleOutputsOK;
    @Metric({"Connections","# of current shuffle connections"})
    MutableGaugeInt shuffleConnections;

    @Override
    public void operationComplete(ChannelFuture future) throws Exception {
      if (future.isSuccess()) {
        shuffleOutputsOK.incr();
      } else {
        shuffleOutputsFailed.incr();
      }
      shuffleConnections.decr();
    }
  }

  final ShuffleMetrics metrics;

  PullServerAuxService(MetricsSystem ms) {
    super("httpshuffle");
    metrics = ms.register(new ShuffleMetrics());
  }

  @SuppressWarnings("UnusedDeclaration")
  public PullServerAuxService() {
    this(DefaultMetricsSystem.instance());
  }

  /**
   * Serialize the shuffle port into a ByteBuffer for use later on.
   * @param port the port to be sent to the ApplciationMaster
   * @return the serialized form of the port.
   */
  public static ByteBuffer serializeMetaData(int port) throws IOException {
    //TODO these bytes should be versioned
    DataOutputBuffer port_dob = new DataOutputBuffer();
    port_dob.writeInt(port);
    return ByteBuffer.wrap(port_dob.getData(), 0, port_dob.getLength());
  }

  /**
   * A helper function to deserialize the metadata returned by PullServerAuxService.
   * @param meta the metadata returned by the PullServerAuxService
   * @return the port the PullServer Handler is listening on to serve shuffle data.
   */
  public static int deserializeMetaData(ByteBuffer meta) throws IOException {
    //TODO this should be returning a class not just an int
    DataInputByteBuffer in = new DataInputByteBuffer();
    in.reset(meta);
    return in.readInt();
  }

  @Override
  public void initializeApplication(ApplicationInitializationContext appInitContext) {
    // TODO these bytes should be versioned
    // TODO: Once SHuffle is out of NM, this can use MR APIs
    this.appId = appInitContext.getApplicationId();
    this.queryId = TajoIdUtils.parseQueryId(appId.toString());
    this.userName = appInitContext.getUser();
    userRsrc.put(this.appId.toString(), this.userName);
  }

  @Override
  public void stopApplication(ApplicationTerminationContext appStopContext) {
    userRsrc.remove(appStopContext.getApplicationId().toString());
  }

  @Override
  public synchronized void init(Configuration conf) {
    try {
      manageOsCache = conf.getBoolean(SHUFFLE_MANAGE_OS_CACHE,
          DEFAULT_SHUFFLE_MANAGE_OS_CACHE);

      readaheadLength = conf.getInt(SHUFFLE_READAHEAD_BYTES,
          DEFAULT_SHUFFLE_READAHEAD_BYTES);

      selector = RpcChannelFactory.createServerChannelFactory("PullServerAuxService", 0)
                  .option(ChannelOption.TCP_NODELAY, true)
                  .childOption(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT)
                  .childOption(ChannelOption.TCP_NODELAY, true);

      localFS = new LocalFileSystem();
      super.init(new Configuration(conf));
    } catch (Throwable t) {
      LOG.error(t);
    }
  }

  // TODO change AbstractService to throw InterruptedException
  @Override
  public synchronized void start() {
    Configuration conf = getConfig();
    ServerBootstrap bootstrap = selector.clone();
    try {
      initializer = new HttpChannelInitializer(conf);
    } catch (Exception ex) {
      throw new RuntimeException(ex);
    }
    bootstrap.channel(NioServerSocketChannel.class)
            .handler(initializer);
    port = conf.getInt(ConfVars.PULLSERVER_PORT.varname,
        ConfVars.PULLSERVER_PORT.defaultIntVal);
    ChannelFuture future = bootstrap.bind(new InetSocketAddress(port))
            .addListener(ChannelFutureListener.FIRE_EXCEPTION_ON_FAILURE)
            .syncUninterruptibly();
    accepted.add(future.channel());
    port = ((InetSocketAddress)future.channel().localAddress()).getPort();
    conf.set(ConfVars.PULLSERVER_PORT.varname, Integer.toString(port));
    initializer.PullServer.setPort(port);
    LOG.info(getName() + " listening on port " + port);
    super.start();

    sslFileBufferSize = conf.getInt(SUFFLE_SSL_FILE_BUFFER_SIZE_KEY,
                                    DEFAULT_SUFFLE_SSL_FILE_BUFFER_SIZE);
  }

  public int getPort() {
    return port;
  }

  @Override
  public synchronized void stop() {
    try {
      accepted.close();
      if (selector != null) {
        if (selector.group() != null) {
          selector.group().shutdownGracefully();
        }
        if (selector.childGroup() != null) {
          selector.childGroup().shutdownGracefully();
        }
      }

      if (initializer != null) {
        initializer.destroy();
      }

      localFS.close();
    } catch (Throwable t) {
      LOG.error(t);
    } finally {
      super.stop();
    }
  }

  @Override
  public synchronized ByteBuffer getMetaData() {
    try {
      return serializeMetaData(port); 
    } catch (IOException e) {
      LOG.error("Error during getMeta", e);
      // TODO add API to AuxiliaryServices to report failures
      return null;
    }
  }

  class HttpChannelInitializer extends ChannelInitializer<Channel> {

    final PullServer PullServer;
    private SSLFactory sslFactory;

    public HttpChannelInitializer(Configuration conf) throws Exception {
      PullServer = new PullServer(conf);
      if (conf.getBoolean(ConfVars.SHUFFLE_SSL_ENABLED_KEY.varname,
          ConfVars.SHUFFLE_SSL_ENABLED_KEY.defaultBoolVal)) {
        sslFactory = new SSLFactory(SSLFactory.Mode.SERVER, conf);
        sslFactory.init();
      }
    }

    public void destroy() {
      if (sslFactory != null) {
        sslFactory.destroy();
      }
    }

    @Override
    protected void initChannel(Channel channel) throws Exception {
      ChannelPipeline pipeline = channel.pipeline();
      if (sslFactory != null) {
        pipeline.addLast("ssl", new SslHandler(sslFactory.createSSLEngine()));
      }

      pipeline.addLast("encoder", new HttpResponseEncoder());
      pipeline.addLast("decoder", new HttpRequestDecoder());
      pipeline.addLast("aggregator", new HttpObjectAggregator(1 << 16));
      pipeline.addLast("chunking", new ChunkedWriteHandler());
      pipeline.addLast("shuffle", PullServer);
      // TODO factor security manager into pipeline
      // TODO factor out encode/decode to permit binary shuffle
      // TODO factor out decode of index to permit alt. models
    }
  }

  @ChannelHandler.Sharable
  class PullServer extends SimpleChannelInboundHandler<FullHttpRequest> {
    private final Configuration conf;
    private final LocalDirAllocator lDirAlloc = new LocalDirAllocator(ConfVars.WORKER_TEMPORAL_DIR.varname);
    private int port;

    public PullServer(Configuration conf) {
      this.conf = conf;
      this.port = conf.getInt(ConfVars.PULLSERVER_PORT.varname, ConfVars.PULLSERVER_PORT.defaultIntVal);
    }
    
    public void setPort(int port) {
      this.port = port;
    }

    private List<String> splitMaps(List<String> mapq) {
      if (null == mapq) {
        return null;
      }
      final List<String> ret = new ArrayList<String>();
      for (String s : mapq) {
        Collections.addAll(ret, s.split(","));
      }
      return ret;
    }

    @Override
    public void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request)
        throws Exception {
      if (request.getMethod() != HttpMethod.GET) {
        sendError(ctx, HttpResponseStatus.METHOD_NOT_ALLOWED);
        return;
      }

      // Parsing the URL into key-values
      final Map<String, List<String>> params = new QueryStringDecoder(request.getUri()).parameters();
      final List<String> types = params.get("type");
      final List<String> taskIdList = params.get("ta");
      final List<String> stageIds = params.get("sid");
      final List<String> partitionIds = params.get("p");

      if (types == null || taskIdList == null || stageIds == null || partitionIds == null) {
        sendError(ctx, "Required type, taskIds, stage Id, and partition id", HttpResponseStatus.BAD_REQUEST);
        return;
      }

      if (types.size() != 1 || stageIds.size() != 1) {
        sendError(ctx, "Required type, taskIds, stage Id, and partition id", HttpResponseStatus.BAD_REQUEST);
        return;
      }

      final List<FileChunk> chunks = Lists.newArrayList();

      String repartitionType = types.get(0);
      String sid = stageIds.get(0);
      String partitionId = partitionIds.get(0);
      List<String> taskIds = splitMaps(taskIdList);

      // the working dir of tajo worker for each query
      String queryBaseDir = queryId + "/output" + "/";

      LOG.info("PullServer request param: repartitionType=" + repartitionType + ", sid=" + sid + ", partitionId="
          + partitionId + ", taskIds=" + taskIdList);

      String taskLocalDir = conf.get(ConfVars.WORKER_TEMPORAL_DIR.varname);
      if (taskLocalDir == null || taskLocalDir.equals("")) {
        LOG.error("Tajo local directory should be specified.");
      }
      LOG.info("PullServer baseDir: " + taskLocalDir + "/" + queryBaseDir);

      // if a stage requires a range partitioning
      if (repartitionType.equals("r")) {
        String ta = taskIds.get(0);
        Path path = localFS.makeQualified(lDirAlloc.getLocalPathToRead(queryBaseDir + "/" + sid + "/" + ta
            + "/output/", conf));

        String startKey = params.get("start").get(0);
        String endKey = params.get("end").get(0);
        boolean last = params.get("final") != null;

        FileChunk chunk;
        try {
          chunk = getFileCunks(path, startKey, endKey, last);
        } catch (Throwable t) {
          LOG.error("ERROR Request: " + request.getUri(), t);
          sendError(ctx, "Cannot get file chunks to be sent", HttpResponseStatus.BAD_REQUEST);
          return;
        }
        if (chunk != null) {
          chunks.add(chunk);
        }

        // if a stage requires a hash repartition or a scattered hash
        // repartition
      } else if (repartitionType.equals("h") || repartitionType.equals("s")) {
        for (String ta : taskIds) {
          Path path = localFS.makeQualified(lDirAlloc.getLocalPathToRead(queryBaseDir + "/" + sid + "/" + ta
              + "/output/" + partitionId, conf));
          File file = new File(path.toUri());
          FileChunk chunk = new FileChunk(file, 0, file.length());
          chunks.add(chunk);
        }
      } else {
        LOG.error("Unknown repartition type: " + repartitionType);
        return;
      }

      // Write the content.
      if (chunks.size() == 0) {
        HttpResponse response = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.NO_CONTENT);

        if (!HttpHeaders.isKeepAlive(request)) {
          ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
        } else {
          response.headers().set(HttpHeaders.Names.CONNECTION, HttpHeaders.Values.KEEP_ALIVE);
          ctx.writeAndFlush(response);
        }
      } else {
        FileChunk[] file = chunks.toArray(new FileChunk[chunks.size()]);
        ChannelFuture writeFuture = null;
        HttpResponse response = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        long totalSize = 0;
        for (FileChunk chunk : file) {
          totalSize += chunk.length();
        }
        HttpHeaders.setContentLength(response, totalSize);

        if (HttpHeaders.isKeepAlive(request)) {
          response.headers().set(HttpHeaders.Names.CONNECTION, HttpHeaders.Values.KEEP_ALIVE);
        }
        // Write the initial line and the header.
        writeFuture = ctx.write(response);

        for (FileChunk chunk : file) {
          writeFuture = sendFile(ctx, chunk);
          if (writeFuture == null) {
            sendError(ctx, HttpResponseStatus.NOT_FOUND);
            return;
          }
        }
        if (ctx.pipeline().get(SslHandler.class) == null) {
          writeFuture = ctx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT);
        } else {
          ctx.flush();
        }

        // Decide whether to close the connection or not.
        if (!HttpHeaders.isKeepAlive(request)) {
          // Close the connection when the whole content is written out.
          writeFuture.addListener(ChannelFutureListener.CLOSE);
        }
      }

    }

    private ChannelFuture sendFile(ChannelHandlerContext ctx,
                                   FileChunk file) throws IOException {
      RandomAccessFile spill;
      try {
        spill = new RandomAccessFile(file.getFile(), "r");
      } catch (FileNotFoundException e) {
        LOG.info(file.getFile() + " not found");
        return null;
      }

      ChannelFuture lastContentFuture;
      if (ctx.pipeline().get(SslHandler.class) == null) {
        final FadvisedFileRegion partition = new FadvisedFileRegion(spill,
            file.startOffset(), file.length(), manageOsCache, readaheadLength,
            readaheadPool, file.getFile().getAbsolutePath());
        lastContentFuture = ctx.write(partition);
        lastContentFuture.addListener(new FileCloseListener(partition, null, 0, null));
      } else {
        // HTTPS cannot be done with zero copy.
        final FadvisedChunkedFile chunk = new FadvisedChunkedFile(spill,
            file.startOffset(), file.length(), sslFileBufferSize,
            manageOsCache, readaheadLength, readaheadPool,
            file.getFile().getAbsolutePath());
        lastContentFuture = ctx.write(new HttpChunkedInput(chunk));
      }
      metrics.shuffleConnections.incr();
      metrics.shuffleOutputBytes.incr(file.length()); // optimistic
      return lastContentFuture;
    }
    
    private void sendError(ChannelHandlerContext ctx,
        HttpResponseStatus status) {
      sendError(ctx, "", status);
    }

    private void sendError(ChannelHandlerContext ctx, String message,
        HttpResponseStatus status) {
      FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status,
              Unpooled.copiedBuffer(message, CharsetUtil.UTF_8));
      response.headers().set(HttpHeaders.Names.CONTENT_TYPE, "text/plain; charset=UTF-8");

      // Close the connection as soon as the error message is sent.
      ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause)
        throws Exception {
      Channel ch = ctx.channel();
      if (cause instanceof TooLongFrameException) {
        sendError(ctx, HttpResponseStatus.BAD_REQUEST);
        return;
      }

      LOG.error("PullServer error: ", cause);
      if (ch.isActive()) {
        sendError(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR);
      }
    }
  }

  public FileChunk getFileCunks(Path outDir,
                                      String startKey,
                                      String endKey,
                                      boolean last) throws IOException {
    BSTIndex index = new BSTIndex(new TajoConf());
    BSTIndex.BSTIndexReader idxReader =
        index.getIndexReader(new Path(outDir, "index"));
    idxReader.open();
    Schema keySchema = idxReader.getKeySchema();
    TupleComparator comparator = idxReader.getComparator();

    LOG.info("BSTIndex is loaded from disk (" + idxReader.getFirstKey() + ", "
        + idxReader.getLastKey());

    File data = new File(URI.create(outDir.toUri() + "/output"));
    byte [] startBytes = Base64.decodeBase64(startKey);
    byte [] endBytes = Base64.decodeBase64(endKey);

    RowStoreDecoder decoder = RowStoreUtil.createDecoder(keySchema);
    Tuple start;
    Tuple end;
    try {
      start = decoder.toTuple(startBytes);
    } catch (Throwable t) {
      throw new IllegalArgumentException("StartKey: " + startKey
          + ", decoded byte size: " + startBytes.length, t);
    }

    try {
      end = decoder.toTuple(endBytes);
    } catch (Throwable t) {
      throw new IllegalArgumentException("EndKey: " + endKey
          + ", decoded byte size: " + endBytes.length, t);
    }


    if(!comparator.isAscendingFirstKey()) {
      Tuple tmpKey = start;
      start = end;
      end = tmpKey;
    }

    LOG.info("GET Request for " + data.getAbsolutePath() + " (start="+start+", end="+ end +
        (last ? ", last=true" : "") + ")");

    if (idxReader.getFirstKey() == null && idxReader.getLastKey() == null) { // if # of rows is zero
      LOG.info("There is no contents");
      return null;
    }

    if (comparator.compare(end, idxReader.getFirstKey()) < 0 ||
        comparator.compare(idxReader.getLastKey(), start) < 0) {
      LOG.info("Out of Scope (indexed data [" + idxReader.getFirstKey() + ", " + idxReader.getLastKey() +
          "], but request start:" + start + ", end: " + end);
      return null;
    }

    long startOffset;
    long endOffset;
    try {
      startOffset = idxReader.find(start);
    } catch (IOException ioe) {
      LOG.error("State Dump (the requested range: "
          + "[" + start + ", " + end +")" + ", idx min: "
          + idxReader.getFirstKey() + ", idx max: "
          + idxReader.getLastKey());
      throw ioe;
    }
    try {
      endOffset = idxReader.find(end);
      if (endOffset == -1) {
        endOffset = idxReader.find(end, true);
      }
    } catch (IOException ioe) {
      LOG.error("State Dump (the requested range: "
          + "[" + start + ", " + end +")" + ", idx min: "
          + idxReader.getFirstKey() + ", idx max: "
          + idxReader.getLastKey());
      throw ioe;
    }

    // if startOffset == -1 then case 2-1 or case 3
    if (startOffset == -1) { // this is a hack
      // if case 2-1 or case 3
      try {
        startOffset = idxReader.find(start, true);
      } catch (IOException ioe) {
        LOG.error("State Dump (the requested range: "
            + "[" + start + ", " + end +")" + ", idx min: "
            + idxReader.getFirstKey() + ", idx max: "
            + idxReader.getLastKey());
        throw ioe;
      }
    }

    if (startOffset == -1) {
      throw new IllegalStateException("startOffset " + startOffset + " is negative \n" +
          "State Dump (the requested range: "
          + "[" + start + ", " + end +")" + ", idx min: " + idxReader.getFirstKey() + ", idx max: "
          + idxReader.getLastKey());
    }

    // if greater than indexed values
    if (last || (endOffset == -1
        && comparator.compare(idxReader.getLastKey(), end) < 0)) {
      endOffset = data.length();
    }

    FileChunk chunk = new FileChunk(data, startOffset, endOffset - startOffset);
    LOG.info("Retrieve File Chunk: " + chunk);
    return chunk;
  }
}
