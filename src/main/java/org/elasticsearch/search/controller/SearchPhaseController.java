/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.search.controller;

import com.carrotsearch.hppc.IntArrayList;
import com.carrotsearch.hppc.ObjectObjectOpenHashMap;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.*;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.common.collect.HppcMaps;
import org.elasticsearch.common.component.AbstractComponent;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.lucene.Lucene;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.util.BigArrays;
import org.elasticsearch.common.util.concurrent.AtomicArray;
import org.elasticsearch.script.ScriptService;
import org.elasticsearch.search.aggregations.InternalAggregation.ReduceContext;
import org.elasticsearch.search.aggregations.InternalAggregations;
import org.elasticsearch.search.dfs.AggregatedDfs;
import org.elasticsearch.search.dfs.DfsSearchResult;
import org.elasticsearch.search.fetch.FetchSearchResult;
import org.elasticsearch.search.fetch.FetchSearchResultProvider;
import org.elasticsearch.search.internal.InternalSearchHit;
import org.elasticsearch.search.internal.InternalSearchHits;
import org.elasticsearch.search.internal.InternalSearchResponse;
import org.elasticsearch.search.query.QuerySearchResult;
import org.elasticsearch.search.query.QuerySearchResultProvider;
import org.elasticsearch.search.suggest.Suggest;

import java.io.IOException;
import java.util.*;

/**
 *
 */
public class SearchPhaseController extends AbstractComponent {

    public static final Comparator<AtomicArray.Entry<? extends QuerySearchResultProvider>> QUERY_RESULT_ORDERING = new Comparator<AtomicArray.Entry<? extends QuerySearchResultProvider>>() {
        @Override
        public int compare(AtomicArray.Entry<? extends QuerySearchResultProvider> o1, AtomicArray.Entry<? extends QuerySearchResultProvider> o2) {
            int i = o1.value.shardTarget().index().compareTo(o2.value.shardTarget().index());
            if (i == 0) {
                i = o1.value.shardTarget().shardId() - o2.value.shardTarget().shardId();
            }
            return i;
        }
    };

    public static final ScoreDoc[] EMPTY_DOCS = new ScoreDoc[0];

    private final BigArrays bigArrays;
    private final boolean optimizeSingleShard;

    private ScriptService scriptService;

    @Inject
    public SearchPhaseController(Settings settings, BigArrays bigArrays, ScriptService scriptService) {
        super(settings);
        this.bigArrays = bigArrays;
        this.scriptService = scriptService;
        this.optimizeSingleShard = componentSettings.getAsBoolean("optimize_single_shard", true);
    }

    public boolean optimizeSingleShard() {
        return optimizeSingleShard;
    }

    public AggregatedDfs aggregateDfs(AtomicArray<DfsSearchResult> results) {
        ObjectObjectOpenHashMap<Term, TermStatistics> termStatistics = HppcMaps.newNoNullKeysMap();
        ObjectObjectOpenHashMap<String, CollectionStatistics> fieldStatistics = HppcMaps.newNoNullKeysMap();
        long aggMaxDoc = 0;
        for (AtomicArray.Entry<DfsSearchResult> lEntry : results.asList()) {
            final Term[] terms = lEntry.value.terms();
            final TermStatistics[] stats = lEntry.value.termStatistics();
            assert terms.length == stats.length;
            for (int i = 0; i < terms.length; i++) {
                assert terms[i] != null;
                TermStatistics existing = termStatistics.get(terms[i]);
                if (existing != null) {
                    assert terms[i].bytes().equals(existing.term());
                    // totalTermFrequency is an optional statistic we need to check if either one or both
                    // are set to -1 which means not present and then set it globally to -1
                    termStatistics.put(terms[i], new TermStatistics(existing.term(),
                            existing.docFreq() + stats[i].docFreq(),
                            optionalSum(existing.totalTermFreq(), stats[i].totalTermFreq())));
                } else {
                    termStatistics.put(terms[i], stats[i]);
                }

            }
            final boolean[] states = lEntry.value.fieldStatistics().allocated;
            final Object[] keys = lEntry.value.fieldStatistics().keys;
            final Object[] values = lEntry.value.fieldStatistics().values;
            for (int i = 0; i < states.length; i++) {
                if (states[i]) {
                    String key = (String) keys[i];
                    CollectionStatistics value = (CollectionStatistics) values[i];
                    assert key != null;
                    CollectionStatistics existing = fieldStatistics.get(key);
                    if (existing != null) {
                        CollectionStatistics merged = new CollectionStatistics(
                                key, existing.maxDoc() + value.maxDoc(),
                                optionalSum(existing.docCount(), value.docCount()),
                                optionalSum(existing.sumTotalTermFreq(), value.sumTotalTermFreq()),
                                optionalSum(existing.sumDocFreq(), value.sumDocFreq())
                        );
                        fieldStatistics.put(key, merged);
                    } else {
                        fieldStatistics.put(key, value);
                    }
                }
            }
            aggMaxDoc += lEntry.value.maxDoc();
        }
        return new AggregatedDfs(termStatistics, fieldStatistics, aggMaxDoc);
    }

    private static long optionalSum(long left, long right) {
        return Math.min(left, right) == -1 ? -1 : left + right;
    }

    /**
     * @param scrollSort Whether to ignore the from and sort all hits in each shard result. Only used for scroll search
     * @param resultsArr Shard result holder
     */
    public ScoreDoc[] sortDocs(boolean scrollSort, AtomicArray<? extends QuerySearchResultProvider> resultsArr) throws IOException {
        List<? extends AtomicArray.Entry<? extends QuerySearchResultProvider>> results = resultsArr.asList();
        if (results.isEmpty()) {
            return EMPTY_DOCS;
        }

        if (optimizeSingleShard) {
            boolean canOptimize = false;
            QuerySearchResult result = null;
            int shardIndex = -1;
            if (results.size() == 1) {
                canOptimize = true;
                result = results.get(0).value.queryResult();
                shardIndex = results.get(0).index;
            } else {
                // lets see if we only got hits from a single shard, if so, we can optimize...
                for (AtomicArray.Entry<? extends QuerySearchResultProvider> entry : results) {
                    if (entry.value.queryResult().topDocs().scoreDocs.length > 0) {
                        if (result != null) { // we already have one, can't really optimize
                            canOptimize = false;
                            break;
                        }
                        canOptimize = true;
                        result = entry.value.queryResult();
                        shardIndex = entry.index;
                    }
                }
            }
            if (canOptimize) {
                int offset = result.from();
                if (scrollSort) {
                    offset = 0;
                }
                ScoreDoc[] scoreDocs = result.topDocs().scoreDocs;
                if (scoreDocs.length == 0 || scoreDocs.length < offset) {
                    return EMPTY_DOCS;
                }

                int resultDocsSize = result.size();
                if ((scoreDocs.length - offset) < resultDocsSize) {
                    resultDocsSize = scoreDocs.length - offset;
                }
                ScoreDoc[] docs = new ScoreDoc[resultDocsSize];
                for (int i = 0; i < resultDocsSize; i++) {
                    ScoreDoc scoreDoc = scoreDocs[offset + i];
                    scoreDoc.shardIndex = shardIndex;
                    docs[i] = scoreDoc;
                }
                return docs;
            }
        }

        @SuppressWarnings("unchecked")
        AtomicArray.Entry<? extends QuerySearchResultProvider>[] sortedResults = results.toArray(new AtomicArray.Entry[results.size()]);
        Arrays.sort(sortedResults, QUERY_RESULT_ORDERING);
        QuerySearchResultProvider firstResult = sortedResults[0].value;

        final Sort sort;
        if (firstResult.queryResult().topDocs() instanceof TopFieldDocs) {
            TopFieldDocs firstTopDocs = (TopFieldDocs) firstResult.queryResult().topDocs();
            sort = new Sort(firstTopDocs.fields);
        } else {
            sort = null;
        }

        int topN = firstResult.queryResult().size();
        // Need to use the length of the resultsArr array, since the slots will be based on the position in the resultsArr array
        TopDocs[] shardTopDocs = new TopDocs[resultsArr.length()];
        if (firstResult.includeFetch()) {
            // if we did both query and fetch on the same go, we have fetched all the docs from each shards already, use them...
            // this is also important since we shortcut and fetch only docs from "from" and up to "size"
            topN *= sortedResults.length;
        }
        for (AtomicArray.Entry<? extends QuerySearchResultProvider> sortedResult : sortedResults) {
            TopDocs topDocs = sortedResult.value.queryResult().topDocs();
            // the 'index' field is the position in the resultsArr atomic array
            shardTopDocs[sortedResult.index] = topDocs;
        }
        int from = firstResult.queryResult().from();
        if (scrollSort) {
            from = 0;
        }
        // TopDocs#merge can't deal with null shard TopDocs
        for (int i = 0; i < shardTopDocs.length; i++) {
            if (shardTopDocs[i] == null) {
                shardTopDocs[i] = Lucene.EMPTY_TOP_DOCS;
            }
        }
        TopDocs mergedTopDocs = TopDocs.merge(sort, from, topN, shardTopDocs);
        return mergedTopDocs.scoreDocs;
    }

    public ScoreDoc[] getLastEmittedDocPerShard(SearchRequest request, ScoreDoc[] sortedShardList, int numShards) {
        if (request.scroll() != null) {
            return getLastEmittedDocPerShard(sortedShardList, numShards);
        } else {
            return null;
        }
    }

    public ScoreDoc[] getLastEmittedDocPerShard(ScoreDoc[] sortedShardList, int numShards) {
        ScoreDoc[] lastEmittedDocPerShard = new ScoreDoc[numShards];
        for (ScoreDoc scoreDoc : sortedShardList) {
            lastEmittedDocPerShard[scoreDoc.shardIndex] = scoreDoc;
        }
        return lastEmittedDocPerShard;
    }

    /**
     * Builds an array, with potential null elements, with docs to load.
     */
    public void fillDocIdsToLoad(AtomicArray<IntArrayList> docsIdsToLoad, ScoreDoc[] shardDocs) {
        for (ScoreDoc shardDoc : shardDocs) {
            IntArrayList list = docsIdsToLoad.get(shardDoc.shardIndex);
            if (list == null) {
                list = new IntArrayList(); // can't be shared!, uses unsafe on it later on
                docsIdsToLoad.set(shardDoc.shardIndex, list);
            }
            list.add(shardDoc.doc);
        }
    }

    public InternalSearchResponse merge(ScoreDoc[] sortedDocs, AtomicArray<? extends QuerySearchResultProvider> queryResultsArr, AtomicArray<? extends FetchSearchResultProvider> fetchResultsArr) {

        List<? extends AtomicArray.Entry<? extends QuerySearchResultProvider>> queryResults = queryResultsArr.asList();
        List<? extends AtomicArray.Entry<? extends FetchSearchResultProvider>> fetchResults = fetchResultsArr.asList();

        if (queryResults.isEmpty()) {
            return InternalSearchResponse.empty();
        }

        QuerySearchResult firstResult = queryResults.get(0).value.queryResult();

        boolean sorted = false;
        int sortScoreIndex = -1;
        if (firstResult.topDocs() instanceof TopFieldDocs) {
            sorted = true;
            TopFieldDocs fieldDocs = (TopFieldDocs) firstResult.queryResult().topDocs();
            for (int i = 0; i < fieldDocs.fields.length; i++) {
                if (fieldDocs.fields[i].getType() == SortField.Type.SCORE) {
                    sortScoreIndex = i;
                }
            }
        }

        // count the total (we use the query result provider here, since we might not get any hits (we scrolled past them))
        long totalHits = 0;
        float maxScore = Float.NEGATIVE_INFINITY;
        boolean timedOut = false;
        Boolean terminatedEarly = null;
        for (AtomicArray.Entry<? extends QuerySearchResultProvider> entry : queryResults) {
            QuerySearchResult result = entry.value.queryResult();
            if (result.searchTimedOut()) {
                timedOut = true;
            }
            if (result.terminatedEarly() != null) {
                if (terminatedEarly == null) {
                    terminatedEarly = result.terminatedEarly();
                } else if (result.terminatedEarly()) {
                    terminatedEarly = true;
                }
            }
            totalHits += result.topDocs().totalHits;
            if (!Float.isNaN(result.topDocs().getMaxScore())) {
                maxScore = Math.max(maxScore, result.topDocs().getMaxScore());
            }
        }
        if (Float.isInfinite(maxScore)) {
            maxScore = Float.NaN;
        }

        // clean the fetch counter
        for (AtomicArray.Entry<? extends FetchSearchResultProvider> entry : fetchResults) {
            entry.value.fetchResult().initCounter();
        }

        // merge hits
        List<InternalSearchHit> hits = new ArrayList<>();
        if (!fetchResults.isEmpty()) {
            for (ScoreDoc shardDoc : sortedDocs) {
                FetchSearchResultProvider fetchResultProvider = fetchResultsArr.get(shardDoc.shardIndex);
                if (fetchResultProvider == null) {
                    continue;
                }
                FetchSearchResult fetchResult = fetchResultProvider.fetchResult();
                int index = fetchResult.counterGetAndIncrement();
                if (index < fetchResult.hits().internalHits().length) {
                    InternalSearchHit searchHit = fetchResult.hits().internalHits()[index];
                    searchHit.score(shardDoc.score);
                    searchHit.shard(fetchResult.shardTarget());

                    if (sorted) {
                        FieldDoc fieldDoc = (FieldDoc) shardDoc;
                        searchHit.sortValues(fieldDoc.fields);
                        if (sortScoreIndex != -1) {
                            searchHit.score(((Number) fieldDoc.fields[sortScoreIndex]).floatValue());
                        }
                    }

                    hits.add(searchHit);
                }
            }
        }

        // merge suggest results
        Suggest suggest = null;
        if (!queryResults.isEmpty()) {
            final Map<String, List<Suggest.Suggestion>> groupedSuggestions = new HashMap<>();
            boolean hasSuggestions = false;
            for (AtomicArray.Entry<? extends QuerySearchResultProvider> entry : queryResults) {
                Suggest shardResult = entry.value.queryResult().queryResult().suggest();

                if (shardResult == null) {
                    continue;
                }
                hasSuggestions = true;
                Suggest.group(groupedSuggestions, shardResult);
            }

            suggest = hasSuggestions ? new Suggest(Suggest.Fields.SUGGEST, Suggest.reduce(groupedSuggestions)) : null;
        }

        // merge addAggregation
        InternalAggregations aggregations = null;
        if (!queryResults.isEmpty()) {
            if (firstResult.aggregations() != null && firstResult.aggregations().asList() != null) {
                List<InternalAggregations> aggregationsList = new ArrayList<>(queryResults.size());
                for (AtomicArray.Entry<? extends QuerySearchResultProvider> entry : queryResults) {
                    aggregationsList.add((InternalAggregations) entry.value.queryResult().aggregations());
                }
                aggregations = InternalAggregations.reduce(aggregationsList, new ReduceContext(null, bigArrays, scriptService));
            }
        }

        InternalSearchHits searchHits = new InternalSearchHits(hits.toArray(new InternalSearchHit[hits.size()]), totalHits, maxScore);

        return new InternalSearchResponse(searchHits, aggregations, suggest, timedOut, terminatedEarly);
    }

}
