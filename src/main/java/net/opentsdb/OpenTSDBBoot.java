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

import net.opentsdb.core.TSDB;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
		// TODO Auto-generated method stub

	}

}
