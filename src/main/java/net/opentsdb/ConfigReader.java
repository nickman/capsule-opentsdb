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

import java.beans.PropertyEditor;
import java.beans.PropertyEditorManager;
import java.io.InputStream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import javax.script.ScriptException;

import net.opentsdb.utils.Config;
import net.opentsdb.utils.JSON;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.heliosapm.utils.config.ConfigurationHelper;

/**
 * <p>Title: ConfigReader</p>
 * <p>Description: Reads the json based configuration plan and creates an OpenTSDB {@link Config} to boot.</p> 
 * <p>Company: Helios Development Group LLC</p>
 * @author Whitehead (nwhitehead AT heliosdev DOT org)
 * <p><code>net.opentsdb.ConfigReader</code></p>
 */

public class ConfigReader {

	/** The path to load the config json from */
	public static String CONFIG_PATH = "config/opentsdb.json";
	
	/** Pattern matcher and resolver for configuration expressions */
	public static Pattern EXPR_RESOLVER = Pattern.compile("\\$\\{(.*?)(?::(.*))?\\}$");
	/** Expression operator and arg extractor */
	public static Pattern EXPR_EXTRACTOR = Pattern.compile("(.*?)\\{(.*)?\\}");
	
	/** The script engine to resolve js configured items */
	private static final ScriptEngine jsEngine;
	
	static {
		final ScriptEngineManager sem = new ScriptEngineManager(ClassLoader.getSystemClassLoader());
		jsEngine = sem.getEngineByExtension("js");
	}
	
	/**
	 * Creates a new ConfigReader
	 */
	private ConfigReader() {
	}
	
	public static void main(String[] args) {
		System.setProperty("capsule.cache.dir", "/tmp");
		ConfigReader cr = load();
	}
	
	public static ConfigReader load() {
		final ConfigReader cr = new ConfigReader();
		InputStream is = null;
		try {
			is = ConfigReader.class.getClassLoader().getResourceAsStream(CONFIG_PATH);
			ArrayNode an = (ArrayNode)JSON.getMapper().readTree(is);
			for(int i = 0; i < an.size(); i++) {
				JsonNode jn = an.get(i);
				ConfigurationItem ci = JSON.getMapper().convertValue(jn, ConfigurationItem.class);
				ci.resolve();
				System.out.println(ci);
			}
			
			
			return cr;
		} catch (Exception ex) {
			throw new RuntimeException("Failed to load resource [" + CONFIG_PATH + "]", ex);
		}
		
	}
	
	
	
	/**
	 * <p>Title: ConfigurationItem</p>
	 * <p>Description: Wraps a json based config item definition</p> 
	 * <p>Company: Helios Development Group LLC</p>
	 * @author Whitehead (nwhitehead AT heliosdev DOT org)
	 * <p><code>net.opentsdb.ConfigReader.ConfigurationItem</code></p>
	 */
	public static class ConfigurationItem {
		/** The configuration category */
		@JsonProperty("category")
		String category;
		/** The configuration item description */
		@JsonProperty("description")
		String description;
		/** Indicates if the the configuration item is mandatory  */
		@JsonProperty("optional")
		boolean optional;
		/** The configuration item resolved value */		
		Object value;
		/** The configuration item key */
		@JsonProperty("key")
		String key;		
		/** The configuration item type */
		Class<?> type;
		
		/** The temp value until the value and type are set */
		@JsonIgnore
		transient String tmpValue = null;
		
		ConfigurationItem() {
			
		}

		/**
		 * Returns the configuration item's category
		 * @return the category
		 */
		public String getCategory() {
			return category;
		}
		
		@JsonProperty("type")
		void setType(final String typeName) {
			if(typeName==null || typeName.trim().isEmpty()) throw new IllegalArgumentException("The passed type name was null or empty");
			try {
				if(typeName.indexOf('.')==-1) {
					type = Class.forName("java.lang." + typeName.trim());
				} else {
					type = Class.forName(typeName.trim());
				}
			} catch (Exception ex) {
				throw new IllegalArgumentException("The passed type name [" + typeName + "] could not be resolved");
			}
		}
		
		@JsonProperty("value")
		void setValue(final Object v) {
			if(v==null || v.toString().trim().isEmpty()) {
				value = null;
			} else if(!(v instanceof String)) {
				this.value = v;
			} else {
				final String valueExpr = v.toString().trim();
				if(valueExpr==null || valueExpr.isEmpty()) { //throw new IllegalArgumentException("The passed value expression was null or empty");
					value = null;
				} else {
					final String expr = valueExpr.trim();
					final Matcher outerm = EXPR_RESOLVER.matcher(expr);
					if(!outerm.matches()) {
						tmpValue = expr;
					} else {
						final String sysEnvKey = outerm.group(1).trim();
						String defaultValue = outerm.group(2);
						if(defaultValue!=null && !defaultValue.trim().isEmpty()) {
							defaultValue = defaultValue.trim();
							defaultValue = resolveInner(defaultValue);
							tmpValue = ConfigurationHelper.getSystemThenEnvProperty(sysEnvKey, defaultValue);
						} else {
							tmpValue = ConfigurationHelper.getSystemThenEnvProperty(sysEnvKey, null);
						}
					}
				}				
			}			
		}
		
		void resolve() {
			if(value==null) {
				if(tmpValue==null || tmpValue.trim().isEmpty()) {
					value = null;
				} else {
					if(type==null || tmpValue.trim().isEmpty()) throw new IllegalStateException("Type or tmp value not set: type:" + type + ", tmpVal:" + tmpValue);
					try {
						PropertyEditor pe = PropertyEditorManager.findEditor(type);
						pe.setAsText(tmpValue);
						value = pe.getValue();
					} catch (Exception ex) {
						throw new RuntimeException("Failed to convert value [" + tmpValue + "] to [" + type.getName() + "]", ex);
					}
				}
			}
		}
		
		
		String resolveInner(final String expr) {
			final Matcher m = EXPR_EXTRACTOR.matcher(expr);
			final StringBuffer sb = new StringBuffer();
			while(m.find()) {
				final String op = m.group(1);
				final String args = m.group(2);
				final String resolved = _resolveInner(op, args);
				m.appendReplacement(sb, resolved);
			}
			m.appendTail(sb);
			return sb.toString();
		}
		
		String _resolveInner(final String op, final String expr) {
			if(op==null || op.trim().isEmpty()) throw new IllegalStateException("The decoded op was null or empty");
			if(expr==null || expr.trim().isEmpty()) throw new IllegalStateException("The decoded expr was null or empty");			
			if("$".equals(op.trim())) {
				return ConfigurationHelper.getSystemThenEnvProperty(expr, null);
			} else if("js".equalsIgnoreCase(op.trim())) {
				try {
					Object obj = jsEngine.eval(expr.trim());
					if(obj!=null) return obj.toString();
					return null;
				} catch (ScriptException e) {
					throw new RuntimeException("Failed to evaluate js expression [" + expr + "]", e);
				}
			} else {
				throw new RuntimeException("The op [" + op + "] was not recognized");
			}
		}

		/**
		 * Returns the configuration item's description
		 * @return the description
		 */
		public String getDescription() {
			return description;
		}

		/**
		 * Returns the configuration item's optionallity
		 * @return the optional
		 */
		public boolean isOptional() {
			return optional;
		}

		/**
		 * Returns the configuration item's value
		 * @return the value
		 */
		public Object getValue() {
			return value;
		}

		/**
		 * Returns the configuration item's key
		 * @return the key
		 */
		public String getKey() {
			return key;
		}

		/**
		 * {@inheritDoc}
		 * @see java.lang.Object#toString()
		 */
		@Override
		public String toString() {
			StringBuilder builder = new StringBuilder();
			builder.append("ConfigurationItem [key=");
			builder.append(key);
			builder.append(", value=");
			builder.append(value);
			builder.append(", type=");
			builder.append(type.getName());			
			builder.append("]");
			return builder.toString();
		}

		/**
		 * {@inheritDoc}
		 * @see java.lang.Object#hashCode()
		 */
		@Override
		public int hashCode() {
			final int prime = 31;
			int result = 1;
			result = prime * result + ((key == null) ? 0 : key.hashCode());
			return result;
		}

		/**
		 * {@inheritDoc}
		 * @see java.lang.Object#equals(java.lang.Object)
		 */
		@Override
		public boolean equals(Object obj) {
			if (this == obj)
				return true;
			if (obj == null)
				return false;
			if (getClass() != obj.getClass())
				return false;
			ConfigurationItem other = (ConfigurationItem) obj;
			if (key == null) {
				if (other.key != null)
					return false;
			} else if (!key.equals(other.key))
				return false;
			return true;
		}
		
		
		
	}
	
//  "category": "Plugins",
//  "description": "The class name of a real time publishing plugin to instantiate. If is set to false, this value is ignored. E.g. net.opentsdb.tsd.RabbitMQPublisher",
//  "optional": "true",
//  "value": "${tsd.rtpublisher.plugin:}",
//  "type": "String",
//  "key": "tsd.rtpublisher.plugin"	

}
