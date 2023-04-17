/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.solr.handler.admin.api;

import static org.apache.solr.client.solrj.SolrRequest.METHOD.POST;
import static org.apache.solr.common.params.CommonParams.ACTION;
import static org.apache.solr.common.params.CommonParams.NAME;
import static org.apache.solr.handler.ClusterAPI.wrapParams;
import static org.apache.solr.handler.admin.api.CreateCollectionAPI.convertV2CreateCollectionMapToV1ParamMap;
import static org.apache.solr.security.PermissionNameProvider.Name.COLL_EDIT_PERM;

import java.util.HashMap;
import java.util.Map;
import org.apache.solr.api.Command;
import org.apache.solr.api.EndPoint;
import org.apache.solr.api.PayloadObj;
import org.apache.solr.client.solrj.request.beans.RestoreCollectionPayload;
import org.apache.solr.client.solrj.request.beans.V2ApiConstants;
import org.apache.solr.common.params.CollectionParams;
import org.apache.solr.handler.admin.CollectionsHandler;

@EndPoint(
    path = {"/backups/{backupName}"},
    method = POST,
    permission = COLL_EDIT_PERM)
public class RestoreCollectionAPI {

  public static final String V2_RESTORE_CMD = "restore-collection";

  private final CollectionsHandler collectionsHandler;

  public RestoreCollectionAPI(CollectionsHandler collectionsHandler) {
    this.collectionsHandler = collectionsHandler;
  }

  @Command(name = V2_RESTORE_CMD)
  @SuppressWarnings("unchecked")
  public void restoreBackup(PayloadObj<RestoreCollectionPayload> obj) throws Exception {
    final RestoreCollectionPayload v2Body = obj.get();
    final Map<String, Object> v1Params = v2Body.toMap(new HashMap<>());

    v1Params.put(ACTION, CollectionParams.CollectionAction.RESTORE.toLower());
    v1Params.put(NAME, obj.getRequest().getPathTemplateValues().get("backupName"));
    if (v2Body.createCollectionParams != null && !v2Body.createCollectionParams.isEmpty()) {
      final Map<String, Object> createCollParams =
          (Map<String, Object>) v1Params.remove(V2ApiConstants.CREATE_COLLECTION_KEY);
      convertV2CreateCollectionMapToV1ParamMap(createCollParams);
      v1Params.putAll(createCollParams);
    }

    collectionsHandler.handleRequestBody(wrapParams(obj.getRequest(), v1Params), obj.getResponse());
  }
}
