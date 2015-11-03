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



import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.URL;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import javax.management.ObjectName;

import net.opentsdb.core.TSDB;
import net.opentsdb.tools.BuildData;
import net.opentsdb.utils.Config;

import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.buffer.ChannelBuffers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.heliosapm.utils.file.FileFilterBuilder;
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

	/** Boot classes keyed by the commands we recognize */
	public static final Map<String, Class<?>> COMMANDS;
	
	static {
		Map<String, Class<?>> tmp = new HashMap<String, Class<?>>();
//		tmp.put("fsck", Fsck.class);
//		tmp.put("import", TextImporter.class);
//		tmp.put("mkmetric", UidManager.class); // -> shift --> set uid assign metrics "$@"
//		tmp.put("query", CliQuery.class);
//		tmp.put("tsd", Main.class);
//		tmp.put("scan", DumpSeries.class);
//		tmp.put("uid", UidManager.class);
//		tmp.put("exportui", UIContentExporter.class);
//		tmp.put("help", HelpProcessor.class);
		COMMANDS = Collections.unmodifiableMap(tmp);
	}
	
	
	
	/**
	 * Creates a new OpenTSDBBoot
	 */
	public OpenTSDBBoot() {
		// TODO Auto-generated constructor stub
	}

	/**
	 * @param args
	 */
	public static void main(String[] args) {
    Logger log = LoggerFactory.getLogger(OpenTSDBBoot.class);
    log.info("Starting.");
    log.info(BuildData.revisionString());
    log.info(BuildData.buildString());
		
		System.setProperty("capsule.cache.dir", "/tmp");
		try {
			final Config config = new Config(false);
			String[] cmdLineArgs = ConfigReader.load(config, args); // new String[]{"--port", "3847"}
//			System.out.println("CMD Line Args:" + Arrays.toString(cmdLineArgs));
//			System.out.println("TSDB Config:" + config.dumpConfiguration());
			loadContent(config.getDirectoryName("tsd.http.staticroot"));
			final File cacheDir = new File(config.getDirectoryName("tsd.http.cachedir"));
			if(!cacheDir.exists()) {
				if(!cacheDir.mkdirs()) {
					throw new IllegalArgumentException("Failed to create cache directory [" + cacheDir + "]");
				}
			}
			final ObjectName bossPoolObjectName = new ObjectName("net.opentsdb.server:service=NettyBossPool");
			final ObjectName workerPoolObjectName = new ObjectName("net.opentsdb.server:service=NettyWorkerPool");
			final JMXManagedThreadPool bossPool = new JMXManagedThreadPool(bossPoolObjectName, "BossPool", 8, 8, 1, 60000, 100, 99, true);
			final JMXManagedThreadPool workerPool = new JMXManagedThreadPool(workerPoolObjectName, "WorkerPool", config.getInt("tsd.network.worker_threads"), config.getInt("tsd.network.worker_threads")+2, 1, 60000, 100, 99, true);
		} catch (Exception ex) {
			ex.printStackTrace(System.err);
			throw new IllegalArgumentException(ex);
		}


	}
	
	/**
	 * Loads the Static UI content files from the classpath JAR to the configured static root directory
	 * @param the name of the content directory to write the content to
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
						log.info("Content File: {}", f);
						final String fileName = f.getAbsolutePath();
						final int index = fileName.indexOf(CP);
						final String targetFileName = cd + File.separator + fileName.substring(index + CPL);
						log.info("Target File: {}", targetFileName);
						final File targetFile = new File(targetFileName);
						if( !targetFile.getParentFile().exists() ) {
							targetFile.getParentFile().mkdirs();
						}
						if(targetFile.exists() ) {
							if(targetFile.lastModified() >= contentTime ) {
								log.debug("File in directory was newer [{}]", targetFile.getName());
								fileNewer++;
								continue;
							}
							targetFile.delete();
						}
						log.debug("Writing content file [{}]", targetFile );
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
							log.debug("Wrote content file [{}] + with size [{}]", targetFile, contentSize );
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
	

}
