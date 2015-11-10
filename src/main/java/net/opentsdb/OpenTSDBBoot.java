/**
Licensed to the Apache Software Foundation (ASF) under one
or more contributor license agreements.  See the NOTICE file
distributed with this work for additional information
regarding copyright ownership.  The ASF licenses this file
to you under the Apache License, Version 2.0 (the
"License"); you may not use this file except in compliance
with the License.  You may obtain a copy of the License at

  http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing,
software distributed under the License is distributed on an
"AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
KIND, either express or implied.  See the License for the
specific language governing permissions and limitations
under the License.
 */
package net.opentsdb;



import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URL;
import java.util.Enumeration;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import javax.management.ObjectName;

import net.opentsdb.core.TSDB;
import net.opentsdb.tools.BuildData;
import net.opentsdb.tools.TSDBTools.Tool;
import net.opentsdb.tsd.PipelineFactory;
import net.opentsdb.tsd.RpcManager;
import net.opentsdb.utils.Config;
import net.opentsdb.utils.Threads;

import org.jboss.netty.bootstrap.ServerBootstrap;
import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.buffer.ChannelBuffers;
import org.jboss.netty.channel.socket.ServerSocketChannelFactory;
import org.jboss.netty.channel.socket.nio.NioServerBossPool;
import org.jboss.netty.channel.socket.nio.NioServerSocketChannelFactory;
import org.jboss.netty.channel.socket.nio.NioWorkerPool;
import org.jboss.netty.channel.socket.oio.OioServerSocketChannelFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.heliosapm.utils.file.FileFilterBuilder;
import com.heliosapm.utils.jmx.JMXHelper;
import com.heliosapm.utils.jmx.JMXManagedThreadPool;

/**
 * <p>Title: OpenTSDBBoot</p>
 * <p>Description: Capsule supported bootstrap for OpenTSDB Capsule</p> 
 * <p>Company: Helios Development Group LLC</p>
 * @author Whitehead (nwhitehead AT heliosdev DOT org)
 * <p><code>net.opentsdb.OpenTSDBBoot</code></p>
 * TODO: <ol>
 *  <li>Check for start command:  <ol>
 *  	<li>tsd or none: start tsd</li>
 *  	<li>config: start config editor</li>
 *  	<li>help: print command line options</li>
 *  </ol></li>
 * 	<li>Read capsule opentsdb.conf and load Config</li>
 * 	<li>Apply command line overrides</li>
 *  <li>Check/Create cache directory</li>
 * 	<li>Write/Refresh UI Content</li>
 * 	<li>Write/Refresh gnuplot shell scripts</li>
 * 	<li>Log versions</li>
 * 	<li>Create executor</li>
 * 	<li>Create Netty Thread Pools</li>
 * 	<li>Create NioServerSocketChannelFactory / OioServerSocketChannelFactory</li>
 * 	<li></li>
 * 	<li></li>
 * </ol>
 */

public class OpenTSDBBoot {
	/** Static class logger */
	private static final Logger log = LoggerFactory.getLogger(OpenTSDBBoot.class);
	/** The core tsdb instance */
	private static TSDB tsdb = null;

	/** The platform EOL string */
	public static final String EOL = System.getProperty("line.separator", "\n");
	/** The content prefix  */
	public static final String CONTENT_PREFIX = "ui";

	/** The default pid file directory which is <b><code>${user.home}/.tsdb/</code></b> */
	public static final File DEFAULT_PID_DIR = new File(System.getProperty("user.home") + File.separator + ".tsdb");
	/** The default pid file which is <b><code>${user.home}/.tsdb/opentsdb.pid</code></b> */
	public static final File DEFAULT_PID_FILE = new File(DEFAULT_PID_DIR, "opentsdb.pid");

	/** The default flush interval */
	public static final short DEFAULT_FLUSH_INTERVAL = 1000;
	
	/**
	 * Creates a new OpenTSDBBoot
	 */
	public OpenTSDBBoot() {
	}

	/**
	 * @param args
	 */
	public static void main(String[] args) {
    Logger log = LoggerFactory.getLogger(OpenTSDBBoot.class);
    if(args.length>0) {
    	final String toolName = args[1];
    	if(Tool.isTool(toolName)) {
    		Tool.invoke(toolName, shift(args));
    		return;
    	}
    }
    
    
    
    log.info("Starting TSDB.");
    log.info(BuildData.revisionString());
    log.info(BuildData.buildString());
		
		System.setProperty("capsule.cache.dir", "/tmp");
		try {
			final Config config = new Config(false);
			final String[] cmdLineArgs = ConfigReader.load(config, args); // new String[]{"--port", "3847"}
			//  FOR CAPSULE:  how can we add these additional command line args ?
//			System.out.println("CMD Line Args:" + Arrays.toString(cmdLineArgs));
//			System.out.println("TSDB Config:" + config.dumpConfiguration());
			loadContent(config.getDirectoryName("tsd.http.staticroot"));
			GnuplotInstaller.installMyGnuPlot();
			final File cacheDir = new File(config.getDirectoryName("tsd.http.cachedir"));
			if(!cacheDir.exists()) {
				if(!cacheDir.mkdirs()) {
					throw new IllegalArgumentException("Failed to create cache directory [" + cacheDir + "]");
				}
			}
			// ======================================================
			//		JMXMP Server
			// ======================================================
			JMXHelper.fireUpJMXMPServer(config.getString("jmxmp.network.bind"), config.getInt("jmxmp.network.port"), JMXHelper.getHeliosMBeanServer());
			// ======================================================
			//		Netty Setup
			// ======================================================
			// The underlying executor
			final ObjectName nettyThreadPoolObjectName = new ObjectName("net.opentsdb.server:service=NettyThreadPool");
			final JMXManagedThreadPool threadPool = new JMXManagedThreadPool(nettyThreadPoolObjectName, "NettyThreadPool", 1, Integer.MAX_VALUE, 1, 60000, 100, 99, true);
			
			final ServerSocketChannelFactory factory;
			if (config.getBoolean("tsd.network.async_io")) {
	      int workers = Runtime.getRuntime().availableProcessors() * 2;
	      if (config.hasProperty("tsd.network.worker_threads")) {
	        workers = config.getInt("tsd.network.worker_threads");
	      }				
	      final NioServerBossPool boss_pool = new NioServerBossPool(threadPool, 1, new Threads.BossThreadNamer());
	      final NioWorkerPool worker_pool = new NioWorkerPool(threadPool, workers, new Threads.WorkerThreadNamer());	      
	      factory = new NioServerSocketChannelFactory(boss_pool, worker_pool);	      
			} else {
	      factory = new OioServerSocketChannelFactory(threadPool, threadPool, new Threads.PrependThreadNamer());				
			}
	    try {
	      tsdb = new TSDB(config);
	      tsdb.initializePlugins(true);
	      if (config.getBoolean("tsd.storage.hbase.prefetch_meta")) {
	        tsdb.preFetchHBaseMeta();
	      }
	      // Make sure we don't even start if we can't find our tables.
	      tsdb.checkNecessaryTablesExist().joinUninterruptibly();
	      registerShutdownHook();
	      final ServerBootstrap server = new ServerBootstrap(factory);
	      // This manager is capable of lazy init, but we force an init
	      // here to fail fast.
	      final RpcManager manager = RpcManager.instance(tsdb);
	      server.setPipelineFactory(new PipelineFactory(tsdb, manager));
	      if (config.hasProperty("tsd.network.backlog")) {
	        server.setOption("backlog", config.getInt("tsd.network.backlog")); 
	      }
	      server.setOption("child.tcpNoDelay", 
	          config.getBoolean("tsd.network.tcp_no_delay"));
	      server.setOption("child.keepAlive", 
	          config.getBoolean("tsd.network.keep_alive"));
	      server.setOption("reuseAddress", 
	          config.getBoolean("tsd.network.reuse_address"));

	      // null is interpreted as the wildcard address.
	      InetAddress bindAddress = null;
	      if (config.hasProperty("tsd.network.bind")) {
	        bindAddress = InetAddress.getByName(config.getString("tsd.network.bind"));
	      }

	      // we validated the network port config earlier
	      final InetSocketAddress addr = new InetSocketAddress(bindAddress,
	          config.getInt("tsd.network.port"));
	      server.bind(addr);
	      writePid(config.getString("tsd.process.pid.file"), config.getBoolean("tsd.process.pid.ignore.existing"));
	      log.info("Ready to serve on " + addr);
	    } catch (Throwable e) {
	      factory.releaseExternalResources();
	      try {
	        if (tsdb != null)
	          tsdb.shutdown().joinUninterruptibly();
	      } catch (Exception e2) {
	        log.error("Failed to shutdown HBase client", e2);
	      }
	      throw new RuntimeException("Initialization failed", e);
	    }
	    // The server is now running in separate threads, we can exit main.
		} catch (Exception ex) {
			ex.printStackTrace(System.err);
			throw new IllegalArgumentException(ex);
		}
	}

	private static void registerShutdownHook() {
	  final class TSDBShutdown extends Thread {
	    public TSDBShutdown() {
	      super("TSDBShutdown");
	    }
	    public void run() {
	      try {
	        if (RpcManager.isInitialized()) {
	          // Check that its actually been initialized.  We don't want to
	          // create a new instance only to shutdown!
	          RpcManager.instance(tsdb).shutdown().join();
	        }
	        if (tsdb != null) {
	          tsdb.shutdown().join();
	        }
	      } catch (Exception e) {
	        LoggerFactory.getLogger(TSDBShutdown.class)
	          .error("Uncaught exception during shutdown", e);
	      }
	    }
	  }
	  Runtime.getRuntime().addShutdownHook(new TSDBShutdown());
	}
	

	/**
	 * Drops the first array item in the passed array.
	 * If the passed array is null or empty, returns an empty array
	 * @param args The array to shift
	 * @return the shifted array
	 */
	private static String[] shift(String[] args) {
		if(args==null || args.length==0 | args.length==1) return new String[0];
		String[] newArgs = new String[args.length-1];
		System.arraycopy(args, 1, newArgs, 0, newArgs.length);
		return newArgs;
	}	

	
	/**
	 * Loads the Static UI content files from the classpath JAR to the configured static root directory
	 * @param the name of the content directory to write the content to
	 * FIXME:  Clean this up.
	 */
	private static void contentFromDir(final String contentDirectory) {
		String cd = contentDirectory.trim();
		if(cd.endsWith("/") || cd.endsWith("\\")) {
			cd = cd.substring(0, cd.length()-1);
		}
		final String CP = CONTENT_PREFIX + "/";
		final int CPL = CP.length();
		log.info("Refreshing UI Content in [{}]", contentDirectory);
		File gpDir = new File(contentDirectory);
		final long startTime = System.currentTimeMillis();
		int filesLoaded = 0;
		int fileFailures = 0;
		int fileNewer = 0;
		long bytesLoaded = 0;
		String codeSourcePath = OpenTSDBBoot.class.getProtectionDomain().getCodeSource().getLocation().getPath();
		
		InputStream is = null;
		ChannelBuffer contentBuffer = ChannelBuffers.dynamicBuffer(300000);
		URL url = OpenTSDBBoot.class.getClassLoader().getResource(CP);
		File contentDir = new File(url.getFile());
		if(contentDir.canRead() && contentDir.isDirectory()) {
			final File[] fp = FileFilterBuilder.newBuilder()
					.caseInsensitive(true)
//					.containsMatch(path)
//					.shouldBeFile()						
					.fileFinder()
					.maxDepth(Integer.MAX_VALUE)
					.addSearchDir(contentDir)
					.find();
			try {
					for(File f: fp) {
						if(f.isDirectory()) continue;
						final long contentTime = f.lastModified();
						final long contentSize = f.length();
//						log.info("Content File: {}", f);
						final String fileName = f.getAbsolutePath();
						final int index = fileName.indexOf(CP);
						final String targetFileName = cd + File.separator + fileName.substring(index + CPL);
//						log.info("Target File: {}", targetFileName);
						final File targetFile = new File(targetFileName);
						if( !targetFile.getParentFile().exists() ) {
							targetFile.getParentFile().mkdirs();
						}
						if(targetFile.exists() ) {
							if(targetFile.lastModified() >= contentTime ) {
//								log.debug("File in directory was newer [{}]", targetFile.getName());
								fileNewer++;
								continue;
							}
							targetFile.delete();
						}
//						log.debug("Writing content file [{}]", targetFile );
						targetFile.createNewFile();
						if(!targetFile.canWrite() ) {
							log.warn("Content file [{}] not writable", targetFile);
							fileFailures++;
							continue;
						}
						FileOutputStream fos = null;
						FileInputStream fis = null;
						try {
							fos = new FileOutputStream(targetFile);
							fis = new FileInputStream(f);
							contentBuffer.writeBytes(fis, (int) contentSize);
							contentBuffer.readBytes(fos, (int) contentSize);
							fos.flush();
							fis.close(); fis = null;
							fos.close(); fos = null;
							filesLoaded++;
							bytesLoaded += contentSize;
//							log.debug("Wrote content file [{}] + with size [{}]", targetFile, contentSize );
						} finally {
							if( fis!=null ) try { fis.close(); } catch (Exception ex) {}
							if( fos!=null ) try { fos.close(); } catch (Exception ex) {}
						}
						
					}
					final long elapsed = System.currentTimeMillis()-startTime;
					StringBuilder b = new StringBuilder("\n\n\t===================================================\n\tStatic Root Directory:[").append(contentDirectory).append("]");
					b.append("\n\tTotal Files Written:").append(filesLoaded);
					b.append("\n\tTotal Bytes Written:").append(bytesLoaded);
					b.append("\n\tFile Write Failures:").append(fileFailures);
					b.append("\n\tExisting File Newer Than Content:").append(fileNewer);
					b.append("\n\tElapsed (ms):").append(elapsed);
					b.append("\n\t===================================================\n");
					log.info(b.toString());			
					
				} catch (Exception ex) {
					throw new RuntimeException(ex);
				}
		}
	}
	
	
	/**
	 * Loads the Static UI content files from the classpath JAR to the configured static root directory
	 * @param the name of the content directory to write the content to
	 * FIXME:  Clean this up.
	 */
	private static void loadContent(String contentDirectory) {	
		log.info("Refreshing UI Content in [{}]", contentDirectory);
		File gpDir = new File(contentDirectory);
		final long startTime = System.currentTimeMillis();
		int filesLoaded = 0;
		int fileFailures = 0;
		int fileNewer = 0;
		long bytesLoaded = 0;
		String codeSourcePath = OpenTSDBBoot.class.getProtectionDomain().getCodeSource().getLocation().getPath();
		File file = new File(codeSourcePath);
		if( codeSourcePath.endsWith(".jar") && file.exists() && file.canRead() ) {
			JarFile jar = null;
			ChannelBuffer contentBuffer = ChannelBuffers.dynamicBuffer(300000);
			try {
				jar = new JarFile(file);
				final Enumeration<JarEntry> entries = jar.entries(); 
				while(entries.hasMoreElements()) {
					JarEntry entry = entries.nextElement();
					final String name = entry.getName();
					if (name.startsWith(CONTENT_PREFIX + "/")) { 
						final int contentSize = (int)entry.getSize();
						final long contentTime = entry.getTime();
						if(entry.isDirectory()) {
							new File(gpDir, name).mkdirs();
							continue;
						}
						File contentFile = new File(gpDir, name.replace(CONTENT_PREFIX + "/", ""));
						if( !contentFile.getParentFile().exists() ) {
							contentFile.getParentFile().mkdirs();
						}
						if( contentFile.exists() ) {
							if( contentFile.lastModified() >= contentTime ) {
								log.debug("File in directory was newer [{}]", name);
								fileNewer++;
								continue;
							}
							contentFile.delete();
						}
						log.debug("Writing content file [{}]", contentFile );
						contentFile.createNewFile();
						if( !contentFile.canWrite() ) {
							log.warn("Content file [{}] not writable", contentFile);
							fileFailures++;
							continue;
						}
						FileOutputStream fos = null;
						InputStream jis = null;
						try {
							fos = new FileOutputStream(contentFile);
							jis = jar.getInputStream(entry);
							contentBuffer.writeBytes(jis, contentSize);
							contentBuffer.readBytes(fos, contentSize);
							fos.flush();
							jis.close(); jis = null;
							fos.close(); fos = null;
							filesLoaded++;
							bytesLoaded += contentSize;
							log.debug("Wrote content file [{}] + with size [{}]", contentFile, contentSize );
						} finally {
							if( jis!=null ) try { jis.close(); } catch (Exception ex) {}
							if( fos!=null ) try { fos.close(); } catch (Exception ex) {}
						}
					}  // not content
				} // end of while loop
				final long elapsed = System.currentTimeMillis()-startTime;
				StringBuilder b = new StringBuilder("\n\n\t===================================================\n\tStatic Root Directory:[").append(contentDirectory).append("]");
				b.append("\n\tTotal Files Written:").append(filesLoaded);
				b.append("\n\tTotal Bytes Written:").append(bytesLoaded);
				b.append("\n\tFile Write Failures:").append(fileFailures);
				b.append("\n\tExisting File Newer Than Content:").append(fileNewer);
				b.append("\n\tElapsed (ms):").append(elapsed);
				b.append("\n\t===================================================\n");
				log.info(b.toString());
			} catch (Exception ex) {
				log.error("Failed to export ui content", ex);			  
			} finally {
				if( jar!=null ) try { jar.close(); } catch (Exception x) { /* No Op */}
			}
		}  else {	// end of was-not-a-jar
			contentFromDir(contentDirectory);
		}
	}
	
  /**
   * Writes the PID to the file at the passed location
   * @param file The fully qualified pid file name
   * @param ignorePidFile If true, an existing pid file will be ignored after a warning log
   */
  private static void writePid(String file, boolean ignorePidFile) {
	  File pidFile = new File(file);
	  if(pidFile.exists()) {
		  Long oldPid = getPid(pidFile);
		  if(oldPid==null) {
			  pidFile.delete();
		  } else {
			  log.warn("\n\t==================================\n\tThe OpenTSDB PID file [" + file + "] already exists for PID [" + oldPid + "]. \n\tOpenTSDB might already be running.\n\t==================================\n");
			  if(!ignorePidFile) {
				  log.warn("Exiting due to existing pid file. Start with option --ignore-existing-pid to overwrite"); 
				  System.exit(-1);
			  } else {
				  log.warn("Deleting existing pid file [" + file + "]");
				  pidFile.delete();
			  }
		  }
	  }
	  pidFile.deleteOnExit();
	  File pidDir = pidFile.getParentFile();
	  FileOutputStream fos = null;
	  try {
		  if(!pidDir.exists()) {
			  if(!pidDir.mkdirs()) {
				  throw new Exception("Failed to create PID directory [" + file + "]");
			  }
		  }		  
		  fos = new FileOutputStream(pidFile);		  
		  String PID = ManagementFactory.getRuntimeMXBean().getName().split("@")[0];
		  fos.write(String.format("%s%s", PID, EOL).getBytes());
		  fos.flush();
		  fos.close();
		  fos = null;
		  log.info("PID [" + PID + "] written to pid file [" + file + "]");
	  } catch (Exception ex) {
		  log.error("Failed to write PID file to [" + file + "]", ex);
		  throw new IllegalArgumentException("Failed to write PID file to [" + file + "]", ex);
	  } finally {
		  if(fos!=null) try { fos.close(); } catch (Exception ex) { /* No Op */ }
	  }
  }
	
  /**
   * Reads the pid from the specified pid file
   * @param pidFile The pid file to read from
   * @return The read pid or possibly null / blank if failed to read
   */
  private static Long getPid(File pidFile) {
  	FileReader reader = null;
  	BufferedReader lineReader = null;
  	String pidLine  = null;
  	try {
  		reader = new FileReader(pidFile);
  		lineReader = new BufferedReader(reader);
  		pidLine = lineReader.readLine();
  		if(pidLine!=null) {
  			pidLine = pidLine.trim();			
  		}		
  	} catch (Exception ex) {
  		log.error("Failed to read PID from file  [" + pidFile.getAbsolutePath() + "]", ex);
  	} finally {
  		if(reader!=null) try { reader.close(); } catch (Exception ex) { /* No Op */ }
  	}
  	try {
  		return Long.parseLong(pidLine);
  	} catch (Exception ex) {
  		return null;
  	}	  	
  }

}
