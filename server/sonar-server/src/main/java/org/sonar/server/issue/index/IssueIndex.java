/*
 * SonarQube, open source software quality management tool.
 * Copyright (C) 2008-2014 SonarSource
 * mailto:contact AT sonarsource DOT com
 *
 * SonarQube is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * SonarQube is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package org.sonar.server.issue.index;

import com.google.common.base.Preconditions;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ListMultimap;
import com.google.common.collect.Maps;
import org.apache.commons.lang.BooleanUtils;
import org.elasticsearch.action.search.SearchRequestBuilder;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.action.search.SearchType;
import org.elasticsearch.common.settings.ImmutableSettings;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.index.query.*;
import org.elasticsearch.search.aggregations.AggregationBuilder;
import org.elasticsearch.search.aggregations.AggregationBuilders;
import org.elasticsearch.search.aggregations.bucket.filter.FilterAggregationBuilder;
import org.elasticsearch.search.aggregations.bucket.missing.InternalMissing;
import org.elasticsearch.search.aggregations.bucket.terms.Terms;
import org.elasticsearch.search.sort.FieldSortBuilder;
import org.elasticsearch.search.sort.SortBuilders;
import org.elasticsearch.search.sort.SortOrder;
import org.sonar.api.issue.Issue;
import org.sonar.api.web.UserRole;
import org.sonar.core.issue.db.IssueDto;
import org.sonar.server.issue.IssueQuery;
import org.sonar.server.issue.filter.IssueFilterParameters;
import org.sonar.server.search.*;

import javax.annotation.Nullable;

import java.util.*;

import static com.google.common.collect.Lists.newArrayList;

public class IssueIndex extends BaseIndex<Issue, IssueDto, String> {

  private ListMultimap<String, IndexField> sortColumns = ArrayListMultimap.create();

  public IssueIndex(IssueNormalizer normalizer, SearchClient client) {
    super(IndexDefinition.ISSUES, normalizer, client);

    sortColumns.put(IssueQuery.SORT_BY_ASSIGNEE, IssueNormalizer.IssueField.ASSIGNEE);
    sortColumns.put(IssueQuery.SORT_BY_STATUS, IssueNormalizer.IssueField.STATUS);
    sortColumns.put(IssueQuery.SORT_BY_SEVERITY, IssueNormalizer.IssueField.SEVERITY_VALUE);
    sortColumns.put(IssueQuery.SORT_BY_CREATION_DATE, IssueNormalizer.IssueField.ISSUE_CREATED_AT);
    sortColumns.put(IssueQuery.SORT_BY_UPDATE_DATE, IssueNormalizer.IssueField.ISSUE_UPDATED_AT);
    sortColumns.put(IssueQuery.SORT_BY_CLOSE_DATE, IssueNormalizer.IssueField.ISSUE_CLOSE_DATE);
    sortColumns.put(IssueQuery.SORT_BY_FILE_LINE, IssueNormalizer.IssueField.PROJECT);
    sortColumns.put(IssueQuery.SORT_BY_FILE_LINE, IssueNormalizer.IssueField.FILE_PATH);
    sortColumns.put(IssueQuery.SORT_BY_FILE_LINE, IssueNormalizer.IssueField.LINE);
  }

  @Override
  protected ImmutableSettings.Builder addCustomIndexSettings(ImmutableSettings.Builder baseIndexSettings) {
    return baseIndexSettings.put("index.number_of_shards", 4);
  }

  @Override
  protected String getKeyValue(String keyString) {
    return keyString;
  }

  @Override
  protected Map mapProperties() {
    Map<String, Object> mapping = new HashMap<String, Object>();
    for (IndexField field : IssueNormalizer.IssueField.ALL_FIELDS) {
      mapping.put(field.field(), mapField(field));
    }
    return mapping;
  }

  @Override
  protected Map mapDomain() {
    Map<String, Object> mapping = new HashMap<String, Object>();
    mapping.put("dynamic", false);
    mapping.put("_all", ImmutableMap.of("enabled", false));
    mapping.put("_id", mapKey());
    mapping.put("_parent", mapParent());
    mapping.put("_routing", mapRouting());
    mapping.put("properties", mapProperties());
    return mapping;
  }

  private Object mapParent() {
    Map<String, Object> mapping = new HashMap<String, Object>();
    mapping.put("type", getParentType());
    return mapping;
  }

  private String getParentType() {
    return IndexDefinition.ISSUES_AUTHORIZATION.getIndexType();
  }

  private Map mapRouting() {
    Map<String, Object> mapping = new HashMap<String, Object>();
    mapping.put("required", true);
    mapping.put("path", IssueNormalizer.IssueField.PROJECT.field());
    return mapping;
  }

  @Override
  protected Map mapKey() {
    Map<String, Object> mapping = new HashMap<String, Object>();
    mapping.put("path", IssueNormalizer.IssueField.KEY.field());
    return mapping;
  }

  @Override
  protected IssueDoc toDoc(Map<String, Object> fields) {
    Preconditions.checkNotNull(fields, "Cannot construct Issue with null response");
    return new IssueDoc(fields);
  }

  @Override
  public Issue getNullableByKey(String key) {
    Result<Issue> result = search(IssueQuery.builder().issueKeys(newArrayList(key)).build(), new QueryContext());
    if (result.getTotal() == 1) {
      return result.getHits().get(0);
    }
    return null;
  }

  public List<FacetValue> listAssignees(IssueQuery query) {
    QueryContext queryContext = new QueryContext().setPage(1, 0);

    SearchRequestBuilder esSearch = getClient()
      .prepareSearch(this.getIndexName())
      .setTypes(this.getIndexType())
      .setIndices(this.getIndexName());

    QueryBuilder esQuery = QueryBuilders.matchAllQuery();
    BoolFilterBuilder esFilter = getFilter(query, queryContext);
    if (esFilter.hasClauses()) {
      esSearch.setQuery(QueryBuilders.filteredQuery(esQuery, esFilter));
    } else {
      esSearch.setQuery(esQuery);
    }
    esSearch.addAggregation(AggregationBuilders.terms(IssueNormalizer.IssueField.ASSIGNEE.field())
      .size(Integer.MAX_VALUE)
      .field(IssueNormalizer.IssueField.ASSIGNEE.field()));
    esSearch.addAggregation(AggregationBuilders.missing("notAssigned")
      .field(IssueNormalizer.IssueField.ASSIGNEE.field()));

    SearchResponse response = getClient().execute(esSearch);
    Terms aggregation = (Terms) response.getAggregations().getAsMap().get(IssueNormalizer.IssueField.ASSIGNEE.field());
    List<FacetValue> facetValues = newArrayList();
    for (Terms.Bucket value : aggregation.getBuckets()) {
      facetValues.add(new FacetValue(value.getKey(), value.getDocCount()));
    }
    facetValues.add(new FacetValue("_notAssigned_", ((InternalMissing) response.getAggregations().get("notAssigned")).getDocCount()));

    return facetValues;
  }

  public Result<Issue> search(IssueQuery query, QueryContext options) {
    SearchRequestBuilder esSearch = getClient()
      .prepareSearch(this.getIndexName())
      .setTypes(this.getIndexType())
      .setIndices(this.getIndexName());

    if (options.isScroll()) {
      esSearch.setSearchType(SearchType.SCAN);
      esSearch.setScroll(TimeValue.timeValueMinutes(3));
    }

    setSorting(query, esSearch);
    setPagination(options, esSearch);

    QueryBuilder esQuery = QueryBuilders.matchAllQuery();
    BoolFilterBuilder esFilter = FilterBuilders.boolFilter();
    Map<String, FilterBuilder> filters = getFilters(query, options);
    for (FilterBuilder filter : filters.values()) {
      if (filter != null) {
        esFilter.must(filter);
      }
    }

    if (esFilter.hasClauses()) {
      esSearch.setQuery(QueryBuilders.filteredQuery(esQuery, esFilter));
    } else {
      esSearch.setQuery(esQuery);
    }

    setFacets(query, options, filters, esQuery, esSearch);

    SearchResponse response = getClient().execute(esSearch);
    return new Result<Issue>(this, response);
  }

  private BoolFilterBuilder getFilter(IssueQuery query, QueryContext options) {
    BoolFilterBuilder esFilter = FilterBuilders.boolFilter();
    for (FilterBuilder filter : getFilters(query, options).values()) {
      if (filter != null) {
        esFilter.must(filter);
      }
    }
    return esFilter;
  }

  public void deleteByProjectUuid(String uuid) {
    QueryBuilder queryBuilder = QueryBuilders.filteredQuery(
      QueryBuilders.matchAllQuery(),
      FilterBuilders.boolFilter().must(FilterBuilders.termsFilter(IssueNormalizer.IssueField.PROJECT.field(), uuid))
      );

    getClient().prepareDeleteByQuery(getIndexName()).setQuery(queryBuilder).get();
  }

  public void deleteByProjectUuidBefore(String uuid, Date beforeDate) {
    // TODO to implement
  }

  /* Build main filter (match based) */
  protected Map<String, FilterBuilder> getFilters(IssueQuery query, QueryContext options) {

    Map<String, FilterBuilder> filters = Maps.newHashMap();

    filters.put("__authorization", getAuthorizationFilter(options));

    // Issue is assigned Filter
    String isAssigned = "__isAssigned";
    if (BooleanUtils.isTrue(query.assigned())) {
      filters.put(isAssigned, FilterBuilders.existsFilter(IssueNormalizer.IssueField.ASSIGNEE.field()));
    } else if (BooleanUtils.isFalse(query.assigned())) {
      filters.put(isAssigned, FilterBuilders.missingFilter(IssueNormalizer.IssueField.ASSIGNEE.field()));
    }

    // Issue is planned Filter
    String isPlanned = "__isPlanned";
    if (BooleanUtils.isTrue(query.planned())) {
      filters.put(isPlanned, FilterBuilders.existsFilter(IssueNormalizer.IssueField.ACTION_PLAN.field()));
    } else if (BooleanUtils.isFalse(query.planned())) {
      filters.put(isPlanned, FilterBuilders.missingFilter(IssueNormalizer.IssueField.ACTION_PLAN.field()));
    }

    // Issue is Resolved Filter
    String isResolved = "__isResolved";
    if (BooleanUtils.isTrue(query.resolved())) {
      filters.put(isResolved, FilterBuilders.existsFilter(IssueNormalizer.IssueField.RESOLUTION.field()));
    } else if (BooleanUtils.isFalse(query.resolved())) {
      filters.put(isResolved, FilterBuilders.missingFilter(IssueNormalizer.IssueField.RESOLUTION.field()));
    }

    // Field Filters
    filters.put(IssueNormalizer.IssueField.KEY.field(), matchFilter(IssueNormalizer.IssueField.KEY, query.issueKeys()));
    filters.put(IssueNormalizer.IssueField.ACTION_PLAN.field(), matchFilter(IssueNormalizer.IssueField.ACTION_PLAN, query.actionPlans()));
    filters.put(IssueNormalizer.IssueField.ASSIGNEE.field(), matchFilter(IssueNormalizer.IssueField.ASSIGNEE, query.assignees()));
    filters.put(IssueNormalizer.IssueField.MODULE_PATH.field(), matchFilter(IssueNormalizer.IssueField.MODULE_PATH, query.componentRootUuids()));
    filters.put(IssueNormalizer.IssueField.COMPONENT.field(), matchFilter(IssueNormalizer.IssueField.COMPONENT, query.componentUuids()));
    filters.put(IssueNormalizer.IssueField.LANGUAGE.field(), matchFilter(IssueNormalizer.IssueField.LANGUAGE, query.languages()));
    filters.put(IssueNormalizer.IssueField.RESOLUTION.field(), matchFilter(IssueNormalizer.IssueField.RESOLUTION, query.resolutions()));
    filters.put(IssueNormalizer.IssueField.REPORTER.field(), matchFilter(IssueNormalizer.IssueField.REPORTER, query.reporters()));
    filters.put(IssueNormalizer.IssueField.RULE_KEY.field(), matchFilter(IssueNormalizer.IssueField.RULE_KEY, query.rules()));
    filters.put(IssueNormalizer.IssueField.SEVERITY.field(), matchFilter(IssueNormalizer.IssueField.SEVERITY, query.severities()));
    filters.put(IssueNormalizer.IssueField.STATUS.field(), matchFilter(IssueNormalizer.IssueField.STATUS, query.statuses()));

    addDatesFilter(filters, query);

    return filters;
  }

  private FilterBuilder getAuthorizationFilter(QueryContext options) {
    String user = options.getUserLogin();
    Set<String> groups = options.getUserGroups();
    OrFilterBuilder groupsAndUser = FilterBuilders.orFilter();
    if (user != null) {
      groupsAndUser.add(FilterBuilders.termFilter(IssueAuthorizationNormalizer.IssueAuthorizationField.USERS.field(), user));
    }
    for (String group : groups) {
      groupsAndUser.add(FilterBuilders.termFilter(IssueAuthorizationNormalizer.IssueAuthorizationField.GROUPS.field(), group));
    }
    return FilterBuilders.hasParentFilter(IndexDefinition.ISSUES_AUTHORIZATION.getIndexType(),
      QueryBuilders.filteredQuery(
        QueryBuilders.matchAllQuery(),
        FilterBuilders.boolFilter()
          .must(FilterBuilders.termFilter(IssueAuthorizationNormalizer.IssueAuthorizationField.PERMISSION.field(), UserRole.USER), groupsAndUser)
          .cache(true))
      );
  }

  private void addDatesFilter(Map<String, FilterBuilder> filters, IssueQuery query) {
    Date createdAfter = query.createdAfter();
    if (createdAfter != null) {
      filters.put("__createdAfter", FilterBuilders
        .rangeFilter(IssueNormalizer.IssueField.ISSUE_CREATED_AT.field())
        .gte(createdAfter));
    }
    Date createdBefore = query.createdBefore();
    if (createdBefore != null) {
      filters.put("__createdBefore", FilterBuilders
        .rangeFilter(IssueNormalizer.IssueField.ISSUE_CREATED_AT.field())
        .lte(createdBefore));
    }
    Date createdAt = query.createdAt();
    if (createdAt != null) {
      filters.put("__createdAt", FilterBuilders.termFilter(IssueNormalizer.IssueField.ISSUE_CREATED_AT.field(), createdAt));
    }
  }

  private void setFacets(IssueQuery query, QueryContext options, Map<String, FilterBuilder> filters, QueryBuilder esQuery, SearchRequestBuilder esSearch) {
    if (options.isFacet()) {
      // Execute Term aggregations
      addSimpleStickyFacetIfNeeded(query, options, filters, esQuery, esSearch,
        IssueFilterParameters.SEVERITIES, IssueNormalizer.IssueField.SEVERITY.field());
      addSimpleStickyFacetIfNeeded(query, options, filters, esQuery, esSearch,
        IssueFilterParameters.STATUSES, IssueNormalizer.IssueField.STATUS.field());
      addSimpleStickyFacetIfNeeded(query, options, filters, esQuery, esSearch,
        IssueFilterParameters.ACTION_PLANS, IssueNormalizer.IssueField.ACTION_PLAN.field(), query.actionPlans().toArray());
      addSimpleStickyFacetIfNeeded(query, options, filters, esQuery, esSearch,
        IssueFilterParameters.COMPONENT_ROOT_UUIDS, IssueNormalizer.IssueField.MODULE_PATH.field(), query.componentRootUuids().toArray());
      addSimpleStickyFacetIfNeeded(query, options, filters, esQuery, esSearch,
        IssueFilterParameters.COMPONENT_UUIDS, IssueNormalizer.IssueField.COMPONENT.field(), query.componentUuids().toArray());
      addSimpleStickyFacetIfNeeded(query, options, filters, esQuery, esSearch,
        IssueFilterParameters.LANGUAGES, IssueNormalizer.IssueField.LANGUAGE.field(), query.languages().toArray());
      addSimpleStickyFacetIfNeeded(query, options, filters, esQuery, esSearch,
        IssueFilterParameters.RULES, IssueNormalizer.IssueField.RULE_KEY.field(), query.rules().toArray());
      addSimpleStickyFacetIfNeeded(query, options, filters, esQuery, esSearch,
        IssueFilterParameters.REPORTERS, IssueNormalizer.IssueField.REPORTER.field());

      if (options.facets().contains(IssueFilterParameters.RESOLUTIONS)) {
        esSearch.addAggregation(getResolutionFacet(query, options, filters, esQuery));
      }
      if (options.facets().contains(IssueFilterParameters.ASSIGNEES)) {
        esSearch.addAggregation(getAssigneesFacet(query, options, filters, esQuery));
      }
    }
  }

  private void addSimpleStickyFacetIfNeeded(IssueQuery query, QueryContext options, Map<String, FilterBuilder> filters, QueryBuilder esQuery, SearchRequestBuilder esSearch,
    String facetName, String fieldName, Object... selectedValues) {
    if (options.facets().contains(facetName)) {
      esSearch.addAggregation(stickyFacetBuilder(esQuery, filters, fieldName, facetName,
        selectedValues));
    }
  }

  private AggregationBuilder getAssigneesFacet(IssueQuery query, QueryContext options, Map<String, FilterBuilder> filters, QueryBuilder esQuery) {
    String fieldName = IssueNormalizer.IssueField.ASSIGNEE.field();
    String facetName = IssueFilterParameters.ASSIGNEES;

    // Same as in super.stickyFacetBuilder
    Map<String, FilterBuilder> assigneeFilters = Maps.newHashMap(filters);
    assigneeFilters.remove("__isAssigned");
    assigneeFilters.remove(fieldName);
    BoolFilterBuilder facetFilter = getStickyFacetFilter(esQuery, assigneeFilters, fieldName);
    FilterAggregationBuilder facetTopAggregation = buildTopFacetAggregation(fieldName, facetName, facetFilter);
    addSelectedItemsToFacet(fieldName, facetName, facetTopAggregation, query.assignees().toArray());

    // Add missing facet for unassigned issues
    facetTopAggregation.subAggregation(
      AggregationBuilders
        .missing(facetName + "_missing")
        .field(fieldName)
      );

    return AggregationBuilders
      .global(facetName)
      .subAggregation(facetTopAggregation);
  }

  private AggregationBuilder getResolutionFacet(IssueQuery query, QueryContext options, Map<String, FilterBuilder> filters, QueryBuilder esQuery) {
    String fieldName = IssueNormalizer.IssueField.RESOLUTION.field();
    String facetName = IssueFilterParameters.RESOLUTIONS;

    // Same as in super.stickyFacetBuilder
    Map<String, FilterBuilder> resolutionFilters = Maps.newHashMap(filters);
    resolutionFilters.remove("__isResolved");
    resolutionFilters.remove(fieldName);
    BoolFilterBuilder facetFilter = getStickyFacetFilter(esQuery, resolutionFilters, fieldName);
    FilterAggregationBuilder facetTopAggregation = buildTopFacetAggregation(fieldName, facetName, facetFilter);
    addSelectedItemsToFacet(fieldName, facetName, facetTopAggregation, query.resolutions().toArray());

    // Add missing facet for unresolved issues
    facetTopAggregation.subAggregation(
      AggregationBuilders
        .missing(facetName + "_missing")
        .field(fieldName)
      );

    return AggregationBuilders
      .global(facetName)
      .subAggregation(facetTopAggregation);
  }

  private void setSorting(IssueQuery query, SearchRequestBuilder esSearch) {
    String sortField = query.sort();
    if (sortField != null) {
      Boolean asc = query.asc();
      List<IndexField> fields = toIndexFields(sortField);
      for (IndexField field : fields) {
        FieldSortBuilder sortBuilder = SortBuilders.fieldSort(field.sortField());
        if (asc != null && asc) {
          sortBuilder.order(SortOrder.ASC);
        } else {
          sortBuilder.order(SortOrder.DESC);
        }
        esSearch.addSort(sortBuilder);
      }
    } else {
      esSearch.addSort(IssueNormalizer.IssueField.ISSUE_UPDATED_AT.sortField(), SortOrder.DESC);
      // deterministic sort when exactly the same updated_at (same millisecond)
      esSearch.addSort(IssueNormalizer.IssueField.KEY.sortField(), SortOrder.ASC);
    }
  }

  private List<IndexField> toIndexFields(String sort) {
    List<IndexField> fields = sortColumns.get(sort);
    if (fields != null) {
      return fields;
    }
    throw new IllegalStateException("Unknown sort field : " + sort);
  }

  protected void setPagination(QueryContext options, SearchRequestBuilder esSearch) {
    esSearch.setFrom(options.getOffset());
    esSearch.setSize(options.getLimit());
  }

  private FilterBuilder matchFilter(IndexField field, @Nullable Collection<?> values) {
    if (values != null && !values.isEmpty()) {
      return FilterBuilders.termsFilter(field.field(), values);
    } else {
      return null;
    }
  }
}
