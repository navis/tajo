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

package org.apache.tajo.catalog;

import com.google.common.base.Objects;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.annotations.Expose;
import org.apache.tajo.SerializeOption;
import org.apache.tajo.catalog.json.CatalogGsonHelper;
import org.apache.tajo.catalog.proto.CatalogProtos;
import org.apache.tajo.catalog.proto.CatalogProtos.StoreType;
import org.apache.tajo.catalog.proto.CatalogProtos.TableProto;
import org.apache.tajo.catalog.proto.CatalogProtos.TableProtoOrBuilder;
import org.apache.tajo.common.ProtoObject;
import org.apache.tajo.json.GsonObject;
import org.apache.tajo.util.KeyValueSet;

import java.util.Map;

/**
 * It contains all information for scanning a fragmented table
 */
public class TableMeta implements ProtoObject<CatalogProtos.TableProto>, GsonObject, Cloneable {
	protected TableProto.Builder builder = null;
  private TableProto proto = TableProto.getDefaultInstance();
  private boolean viaProto = false;

	@Expose protected StoreType storeType;
	@Expose protected KeyValueSet options;
	
	private TableMeta() {
	  builder = TableProto.newBuilder();
	}
	
	public TableMeta(StoreType type, KeyValueSet options) {
	  this();
    this.storeType = type;
    this.options = new KeyValueSet(options);
  }
	
	public TableMeta(TableProto proto) {
    this.proto = proto;
    viaProto = true;
	}
	
	public StoreType getStoreType() {
    TableProtoOrBuilder p = viaProto ? proto : builder;
    if (this.storeType != null) {
      return storeType;
    }
    if (!p.hasStoreType()) {
      return null;
    }
    this.storeType = p.getStoreType();
		return this.storeType;		
	}
	
  public void setOptions(KeyValueSet options) {
    maybeInitBuilder();
    this.options = options;
  }

  public void putOption(String key, String val) {
    maybeInitBuilder();
    options.set(key, val);
  }

  public boolean containsOption(String key) {
    TableProtoOrBuilder p = viaProto ? proto : builder;
    if (options != null) {
      return this.options.containsKey(key);
    }
    if (!p.hasParams()) {
      return false;
    }
    this.options = new KeyValueSet(p.getParams());
    return options.containsKey(key);
  }

  public String getOption(String key) {
    TableProtoOrBuilder p = viaProto ? proto : builder;
    if (options != null) {
      return this.options.get(key);
    }
    if (!p.hasParams()) {
      return null;
    }
    this.options = new KeyValueSet(p.getParams());
    return options.get(key);
  }

  public String getOption(String key, String defaultValue) {
    TableProtoOrBuilder p = viaProto ? proto : builder;
    if (options != null) {
      return this.options.get(key, defaultValue);
    }
    if (!p.hasParams()) {
      return null;
    }
    this.options = new KeyValueSet(p.getParams());
    return options.get(key, defaultValue);
  }

  public KeyValueSet getOptions() {
    TableProtoOrBuilder p = viaProto ? proto : builder;
    if (options != null) {
      return this.options;
    }
    if (!p.hasParams()) {
      return null;
    }
    this.options = new KeyValueSet(p.getParams());
    return options;
  }

  public Map<String,String> toMap() {
    return getOptions().getAllKeyValus();
  }

  @Override
	public boolean equals(Object object) {
		if(object instanceof TableMeta) {
			TableMeta other = (TableMeta) object;
			
			return this.getProto(SerializeOption.GENERIC).equals(other.getProto(SerializeOption.GENERIC));
		}
		
		return false;		
	}

  @Override
	public int hashCode() {
	  return Objects.hashCode(getStoreType(), getOptions());
	}
	
	@Override
	public Object clone() throws CloneNotSupportedException {
	  TableMeta meta = (TableMeta) super.clone();
    meta.builder = TableProto.newBuilder();
    meta.storeType = getStoreType();
    meta.options = (KeyValueSet) (toMap() != null ? options.clone() : null);
    
    return meta;
	}
	
	public String toString() {
	  Gson gson = new GsonBuilder().setPrettyPrinting().
        excludeFieldsWithoutExposeAnnotation().create();
	  return gson.toJson(this);
  }
	
	////////////////////////////////////////////////////////////////////////
	// ProtoObject
	////////////////////////////////////////////////////////////////////////
	public TableProto getProto(SerializeOption option) {
    mergeLocalToProto(option);
    proto = viaProto ? proto : builder.build();
    viaProto = true;
    return proto;
	}

  @Override
	public String toJson(SerializeOption option) {
    mergeProtoToLocal();
		return CatalogGsonHelper.toJson(this, TableMeta.class);
	}

  public void mergeProtoToLocal() {
    getStoreType();
    toMap();
  }

  private void maybeInitBuilder() {
    if (viaProto || builder == null) {
      builder = TableProto.newBuilder(proto);
    }
    viaProto = true;
  }

  private void mergeLocalToBuilder(SerializeOption option) {
    if (storeType != null) {
      builder.setStoreType(storeType);
    }
    if (this.options != null) {
      builder.setParams(options.getProto(option));
    }
  }

  private void mergeLocalToProto(SerializeOption option) {
    if(viaProto) {
      maybeInitBuilder();
    }
    mergeLocalToBuilder(option);
    proto = builder.build();
    viaProto = true;
  }
}
