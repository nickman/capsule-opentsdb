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
package net.opentsdb.tools;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import com.heliosapm.utils.reflect.PrivateAccessor;

/**
 * <p>Title: TSDBTools</p>
 * <p>Description: </p> 
 * <p>Company: Helios Development Group LLC</p>
 * @author Whitehead (nwhitehead AT heliosdev DOT org)
 * <p><code>net.opentsdb.tools.TSDBTools</code></p>
 */

public class TSDBTools {
	
	
	public static interface ToolInvoker {
		public void invoke(final String...args);
	}
	
	public static enum Tool implements ToolInvoker {
		FSCK(Fsck.class),
		IMPORT(TextImporter.class),
		MKMETRIC(UidManager.class),
		QUERY(CliQuery.class),
		SCAN(DumpSeries.class),
		UID(UidManager.class),
		TSD(TSDMain.class),
		SEARCH(Search.class);
		
		
		private Tool(final Class<?> toolClass) {
			this.toolClass = toolClass;
			mainMethod = PrivateAccessor.findMethodFromClass(toolClass, "main", String[].class);
		}
		
		final Class<?> toolClass;
		final Method mainMethod;

		@Override
		public void invoke(String... args) {
			try {
				mainMethod.invoke(null, args);
			} catch (Exception ex) {
				throw new IllegalArgumentException("Failed to invoke [" + name() + "]", ex);
			}
		}
		
		public static boolean isTool(final String name) {
			if(name==null || name.trim().isEmpty()) return false;
			try {
				valueOf(name.trim().toUpperCase());
				return true;
			} catch (Exception ex) {
				return false;
			}
		}
		
		public static void invoke(final String name, final String...args) {
			if(!isTool(name)) throw new IllegalArgumentException("The command [" + name + "] is not a valid command");
			final Tool tool = valueOf(name.trim().toUpperCase());
			tool.invoke(args);
		}
		
	}
	



}
