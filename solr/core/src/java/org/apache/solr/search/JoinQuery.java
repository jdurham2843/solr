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

package org.apache.solr.search;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.LeafReader;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.MultiPostingsEnum;
import org.apache.lucene.index.PostingsEnum;
import org.apache.lucene.index.Terms;
import org.apache.lucene.index.TermsEnum;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.ConstantScoreScorer;
import org.apache.lucene.search.ConstantScoreWeight;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.QueryVisitor;
import org.apache.lucene.search.ScoreMode;
import org.apache.lucene.search.Scorer;
import org.apache.lucene.search.Weight;
import org.apache.lucene.util.Bits;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.FixedBitSet;
import org.apache.lucene.util.StringHelper;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.util.SimpleOrderedMap;
import org.apache.solr.core.CoreContainer;
import org.apache.solr.core.SolrCore;
import org.apache.solr.handler.component.ResponseBuilder;
import org.apache.solr.request.SolrRequestInfo;
import org.apache.solr.schema.SchemaField;
import org.apache.solr.schema.TrieField;
import org.apache.solr.search.join.GraphPointsCollector;
import org.apache.solr.util.RTimer;
import org.apache.solr.util.RefCounted;

class JoinQuery extends Query implements SolrSearcherRequirer {
  String fromField;
  String toField;
  // TODO: name is missleading here compared to JoinQParserPlugin usage - here it must be a core
  // name
  String fromIndex;
  Query q;
  long fromCoreOpenTime;

  public JoinQuery(String fromField, String toField, String coreName, Query subQuery) {
    assert null != fromField;
    assert null != toField;
    assert null != subQuery;

    this.fromField = fromField;
    this.toField = toField;
    this.q = subQuery;

    this.fromIndex = coreName; // may be null
  }

  public Query getQuery() {
    return q;
  }

  @Override
  public Query rewrite(IndexReader reader) throws IOException {
    // don't rewrite the subQuery
    return super.rewrite(reader);
  }

  @Override
  public void visit(QueryVisitor visitor) {
    QueryVisitor sub = visitor.getSubVisitor(BooleanClause.Occur.MUST, this);
    q.visit(sub);
  }

  @Override
  public Weight createWeight(IndexSearcher searcher, ScoreMode scoreMode, float boost)
      throws IOException {
    return new JoinQueryWeight((SolrIndexSearcher) searcher, scoreMode, boost);
  }

  protected class JoinQueryWeight extends ConstantScoreWeight {
    SolrIndexSearcher fromSearcher;
    RefCounted<SolrIndexSearcher> fromRef;
    SolrIndexSearcher toSearcher;
    ResponseBuilder rb;
    ScoreMode scoreMode;

    public JoinQueryWeight(SolrIndexSearcher searcher, ScoreMode scoreMode, float boost) {
      super(JoinQuery.this, boost);
      this.scoreMode = scoreMode;
      this.fromSearcher = searcher;
      SolrRequestInfo info = SolrRequestInfo.getRequestInfo();
      if (info != null) {
        rb = info.getResponseBuilder();
      }

      if (fromIndex == null) {
        this.fromSearcher = searcher;
      } else {
        if (info == null) {
          throw new SolrException(
              SolrException.ErrorCode.BAD_REQUEST, "Cross-core join must have SolrRequestInfo");
        }

        CoreContainer container = searcher.getCore().getCoreContainer();
        final SolrCore fromCore = container.getCore(fromIndex);

        if (fromCore == null) {
          throw new SolrException(
              SolrException.ErrorCode.BAD_REQUEST, "Cross-core join: no such core " + fromIndex);
        }

        if (info.getReq().getCore() == fromCore) {
          // if this is the same core, use the searcher passed in... otherwise we could be warming
          // and get an older searcher from the core.
          fromSearcher = searcher;
        } else {
          // This could block if there is a static warming query with a join in it, and if
          // useColdSearcher is true. Deadlock could result if two cores both had useColdSearcher
          // and had joins that used each other. This would be very predictable though (should
          // happen every time if misconfigured)
          fromRef = fromCore.getSearcher(false, true, null);

          // be careful not to do anything with this searcher that requires the thread local
          // SolrRequestInfo in a manner that requires the core in the request to match
          fromSearcher = fromRef.get();
        }

        if (fromRef != null) {
          final RefCounted<SolrIndexSearcher> ref = fromRef;
          info.addCloseHook(ref::decref);
        }
        info.addCloseHook(fromCore);
      }
      this.toSearcher = searcher;
    }

    DocSet resultSet;

    @Override
    public Scorer scorer(LeafReaderContext context) throws IOException {
      if (resultSet == null) {
        boolean debug = rb != null && rb.isDebug();
        RTimer timer = (debug ? new RTimer() : null);
        resultSet = getDocSet();
        if (timer != null) timer.stop();

        if (debug) {
          SimpleOrderedMap<Object> dbg = new SimpleOrderedMap<>();
          dbg.add("time", (long) timer.getTime());
          dbg.add("fromSetSize", fromSetSize); // the input
          dbg.add("toSetSize", resultSet.size()); // the output

          dbg.add("fromTermCount", fromTermCount);
          dbg.add("fromTermTotalDf", fromTermTotalDf);
          dbg.add("fromTermDirectCount", fromTermDirectCount);
          dbg.add("fromTermHits", fromTermHits);
          dbg.add("fromTermHitsTotalDf", fromTermHitsTotalDf);
          dbg.add("toTermHits", toTermHits);
          dbg.add("toTermHitsTotalDf", toTermHitsTotalDf);
          dbg.add("toTermDirectCount", toTermDirectCount);
          dbg.add("smallSetsDeferred", smallSetsDeferred);
          dbg.add("toSetDocsAdded", resultListDocs);

          // TODO: perhaps synchronize  addDebug in the future...
          rb.addDebug(dbg, "join", JoinQuery.this.toString());
        }
      }

      // Although this set only includes live docs, other filters can be pushed down to queries.
      DocIdSetIterator readerSetIterator = resultSet.iterator(context);
      if (readerSetIterator == null) {
        return null;
      }
      return new ConstantScoreScorer(this, score(), scoreMode, readerSetIterator);
    }

    @Override
    public boolean isCacheable(LeafReaderContext ctx) {
      return false;
    }

    // most of these statistics are only used for the enum method
    int fromSetSize; // number of docs in the fromSet (that match the from query)
    long resultListDocs; // total number of docs collected
    int fromTermCount;
    long fromTermTotalDf;
    int fromTermDirectCount; // number of fromTerms that were too small to use the filter cache
    int fromTermHits; // number of fromTerms that intersected the from query
    long fromTermHitsTotalDf; // sum of the df of the matching terms
    int toTermHits; // num if intersecting from terms that match a term in the to field
    long toTermHitsTotalDf; // sum of the df for the toTermHits
    // number of toTerms that we set directly on a bitset rather than doing set intersections
    int toTermDirectCount;
    // number of small sets collected to be used later to intersect w/ bitset or create another
    // small set
    int smallSetsDeferred;

    public DocSet getDocSet() throws IOException {
      SchemaField fromSchemaField = fromSearcher.getSchema().getField(fromField);
      SchemaField toSchemaField = toSearcher.getSchema().getField(toField);

      boolean usePoints = false;
      if (toSchemaField.getType().isPointField()) {
        if (!fromSchemaField.hasDocValues()) {
          throw new SolrException(
              SolrException.ErrorCode.BAD_REQUEST,
              "join from field "
                  + fromSchemaField
                  + " should have docValues to join with points field "
                  + toSchemaField);
        }
        usePoints = true;
      }

      if (!usePoints) {
        return getDocSetEnumerate();
      }

      // point fields
      GraphPointsCollector collector = new GraphPointsCollector(fromSchemaField, null, null);
      fromSearcher.search(q, collector);
      Query resultQ = collector.getResultQuery(toSchemaField, false);
      // don't cache the resulting docSet... the query may be very large.  Better to cache the
      // results of the join query itself
      DocSet result = resultQ == null ? DocSet.empty() : toSearcher.getDocSetNC(resultQ, null);
      return result;
    }

    public DocSet getDocSetEnumerate() throws IOException {
      FixedBitSet resultBits = null;

      // minimum docFreq to use the cache
      int minDocFreqFrom = Math.max(5, fromSearcher.maxDoc() >> 13);
      int minDocFreqTo = Math.max(5, toSearcher.maxDoc() >> 13);

      // use a smaller size than normal since we will need to sort and dedup the results
      int maxSortedIntSize = Math.max(10, toSearcher.maxDoc() >> 10);

      DocSet fromSet = fromSearcher.getDocSet(q);
      fromSetSize = fromSet.size();

      List<DocSet> resultList = new ArrayList<>(10);

      // make sure we have a set that is fast for random access, if we will use it for that
      Bits fastForRandomSet;
      if (minDocFreqFrom <= 0) {
        fastForRandomSet = null;
      } else {
        fastForRandomSet = fromSet.getBits();
      }

      LeafReader fromReader = fromSearcher.getSlowAtomicReader();
      LeafReader toReader =
          fromSearcher == toSearcher ? fromReader : toSearcher.getSlowAtomicReader();
      Terms terms = fromReader.terms(fromField);
      Terms toTerms = toReader.terms(toField);
      if (terms == null || toTerms == null) return DocSet.empty();
      String prefixStr =
          TrieField.getMainValuePrefix(fromSearcher.getSchema().getFieldType(fromField));
      BytesRef prefix = prefixStr == null ? null : new BytesRef(prefixStr);

      BytesRef term = null;
      TermsEnum termsEnum = terms.iterator();
      TermsEnum toTermsEnum = toTerms.iterator();
      SolrIndexSearcher.DocsEnumState fromDeState = null;
      SolrIndexSearcher.DocsEnumState toDeState = null;

      if (prefix == null) {
        term = termsEnum.next();
      } else {
        if (termsEnum.seekCeil(prefix) != TermsEnum.SeekStatus.END) {
          term = termsEnum.term();
        }
      }

      Bits fromLiveDocs = fromSearcher.getLiveDocsBits();
      Bits toLiveDocs = fromSearcher == toSearcher ? fromLiveDocs : toSearcher.getLiveDocsBits();

      fromDeState = new SolrIndexSearcher.DocsEnumState();
      fromDeState.fieldName = fromField;
      fromDeState.liveDocs = fromLiveDocs;
      fromDeState.termsEnum = termsEnum;
      fromDeState.postingsEnum = null;
      fromDeState.minSetSizeCached = minDocFreqFrom;

      toDeState = new SolrIndexSearcher.DocsEnumState();
      toDeState.fieldName = toField;
      toDeState.liveDocs = toLiveDocs;
      toDeState.termsEnum = toTermsEnum;
      toDeState.postingsEnum = null;
      toDeState.minSetSizeCached = minDocFreqTo;

      while (term != null) {
        if (prefix != null && !StringHelper.startsWith(term, prefix)) break;

        fromTermCount++;

        boolean intersects = false;
        int freq = termsEnum.docFreq();
        fromTermTotalDf++;

        if (freq < minDocFreqFrom) {
          fromTermDirectCount++;
          // OK to skip liveDocs, since we check for intersection with docs matching query
          fromDeState.postingsEnum =
              fromDeState.termsEnum.postings(fromDeState.postingsEnum, PostingsEnum.NONE);
          PostingsEnum postingsEnum = fromDeState.postingsEnum;

          if (postingsEnum instanceof MultiPostingsEnum) {
            MultiPostingsEnum.EnumWithSlice[] subs = ((MultiPostingsEnum) postingsEnum).getSubs();
            int numSubs = ((MultiPostingsEnum) postingsEnum).getNumSubs();
            outer:
            for (int subindex = 0; subindex < numSubs; subindex++) {
              MultiPostingsEnum.EnumWithSlice sub = subs[subindex];
              if (sub.postingsEnum == null) continue;
              int base = sub.slice.start;
              int docid;
              while ((docid = sub.postingsEnum.nextDoc()) != DocIdSetIterator.NO_MORE_DOCS) {
                if (fastForRandomSet.get(docid + base)) {
                  intersects = true;
                  break outer;
                }
              }
            }
          } else {
            int docid;
            while ((docid = postingsEnum.nextDoc()) != DocIdSetIterator.NO_MORE_DOCS) {
              if (fastForRandomSet.get(docid)) {
                intersects = true;
                break;
              }
            }
          }
        } else {
          // use the filter cache
          DocSet fromTermSet = fromSearcher.getDocSet(fromDeState);
          intersects = fromSet.intersects(fromTermSet);
        }

        if (intersects) {
          fromTermHits++;
          fromTermHitsTotalDf++;
          TermsEnum.SeekStatus status = toTermsEnum.seekCeil(term);
          if (status == TermsEnum.SeekStatus.END) break;
          if (status == TermsEnum.SeekStatus.FOUND) {
            toTermHits++;
            int df = toTermsEnum.docFreq();
            toTermHitsTotalDf += df;
            if (resultBits == null
                && df + resultListDocs > maxSortedIntSize
                && resultList.size() > 0) {
              resultBits = new FixedBitSet(toSearcher.maxDoc());
            }

            // if we don't have a bitset yet, or if the resulting set will be too large
            // use the filterCache to get a DocSet
            if (toTermsEnum.docFreq() >= minDocFreqTo || resultBits == null) {
              // use filter cache
              SolrCache<?, ?> filterCache = toSearcher.getFilterCache();
              if (filterCache != null && !filterCache.isRecursionSupported()) {
                throw new SolrException(
                    SolrException.ErrorCode.INVALID_STATE,
                    "Using join queries with synchronous filterCache is not supported! Details can be found in Solr Reference Guide under 'query-settings-in-solrconfig'.");
              }
              DocSet toTermSet = toSearcher.getDocSet(toDeState);
              resultListDocs += toTermSet.size();
              if (resultBits != null) {
                toTermSet.addAllTo(resultBits);
              } else {
                if (toTermSet instanceof BitDocSet) {
                  resultBits = ((BitDocSet) toTermSet).getBits().clone();
                } else {
                  resultList.add(toTermSet);
                }
              }
            } else {
              toTermDirectCount++;

              // need to use liveDocs here so we don't map to any deleted ones
              toDeState.postingsEnum =
                  toDeState.termsEnum.postings(toDeState.postingsEnum, PostingsEnum.NONE);
              toDeState.postingsEnum =
                  BitsFilteredPostingsEnum.wrap(toDeState.postingsEnum, toDeState.liveDocs);
              PostingsEnum postingsEnum = toDeState.postingsEnum;

              if (postingsEnum instanceof MultiPostingsEnum) {
                MultiPostingsEnum.EnumWithSlice[] subs =
                    ((MultiPostingsEnum) postingsEnum).getSubs();
                int numSubs = ((MultiPostingsEnum) postingsEnum).getNumSubs();
                for (int subindex = 0; subindex < numSubs; subindex++) {
                  MultiPostingsEnum.EnumWithSlice sub = subs[subindex];
                  if (sub.postingsEnum == null) continue;
                  int base = sub.slice.start;
                  int docid;
                  while ((docid = sub.postingsEnum.nextDoc()) != DocIdSetIterator.NO_MORE_DOCS) {
                    resultListDocs++;
                    resultBits.set(docid + base);
                  }
                }
              } else {
                int docid;
                while ((docid = postingsEnum.nextDoc()) != DocIdSetIterator.NO_MORE_DOCS) {
                  resultListDocs++;
                  resultBits.set(docid);
                }
              }
            }
          }
        }

        term = termsEnum.next();
      }

      smallSetsDeferred = resultList.size();

      if (resultBits != null) {
        for (DocSet set : resultList) {
          set.addAllTo(resultBits);
        }
        return new BitDocSet(resultBits);
      }

      if (resultList.size() == 0) {
        return DocSet.empty();
      }

      if (resultList.size() == 1) {
        return resultList.get(0);
      }

      int sz = 0;

      for (DocSet set : resultList) sz += set.size();

      int[] docs = new int[sz];
      int pos = 0;
      for (DocSet set : resultList) {
        System.arraycopy(((SortedIntDocSet) set).getDocs(), 0, docs, pos, set.size());
        pos += set.size();
      }
      Arrays.sort(docs);
      int[] dedup = new int[sz];
      pos = 0;
      int last = -1;
      for (int doc : docs) {
        if (doc != last) dedup[pos++] = doc;
        last = doc;
      }

      if (pos != dedup.length) {
        dedup = Arrays.copyOf(dedup, pos);
      }

      return new SortedIntDocSet(dedup, dedup.length);
    }
  }

  @Override
  public String toString(String field) {
    return "{!join from="
        + fromField
        + " to="
        + toField
        + (fromIndex != null ? " fromIndex=" + fromIndex : "")
        + "}"
        + q.toString();
  }

  @Override
  public boolean equals(Object other) {
    return sameClassAs(other) && equalsTo(getClass().cast(other));
  }

  private boolean equalsTo(JoinQuery other) {
    return this.fromField.equals(other.fromField)
        && this.toField.equals(other.toField)
        && this.q.equals(other.q)
        && Objects.equals(fromIndex, other.fromIndex)
        && this.fromCoreOpenTime == other.fromCoreOpenTime;
  }

  @Override
  public int hashCode() {
    int h = classHash();
    h = h * 31 + fromField.hashCode();
    h = h * 31 + toField.hashCode();
    h = h * 31 + q.hashCode();
    h = h * 31 + Objects.hashCode(fromIndex);
    h = h * 31 + (int) fromCoreOpenTime;
    return h;
  }
}
