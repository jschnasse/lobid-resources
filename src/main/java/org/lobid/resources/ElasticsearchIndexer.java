/* Copyright 2013-015 Fabian Steeg, Pascal Christoph, hbz. Licensed under the Eclipse Public License 1.0 */

package org.lobid.resources;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;
import java.util.SortedSet;
import java.util.concurrent.TimeUnit;
import java.util.stream.StreamSupport;

import org.culturegraph.mf.framework.DefaultObjectPipe;
import org.culturegraph.mf.framework.ObjectReceiver;
import org.culturegraph.mf.framework.annotations.In;
import org.culturegraph.mf.framework.annotations.Out;
import org.elasticsearch.action.admin.cluster.state.ClusterStateRequest;
import org.elasticsearch.action.admin.indices.delete.DeleteIndexRequest;
import org.elasticsearch.action.bulk.BulkRequestBuilder;
import org.elasticsearch.action.update.UpdateRequest;
import org.elasticsearch.client.Client;
import org.elasticsearch.client.IndicesAdminClient;
import org.elasticsearch.client.Requests;
import org.elasticsearch.client.transport.NoNodeAvailableException;
import org.elasticsearch.client.transport.TransportClient;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.transport.InetSocketTransportAddress;
import org.elasticsearch.index.query.MultiMatchQueryBuilder;
import org.elasticsearch.rest.action.admin.indices.alias.delete.AliasesNotFoundException;
import org.elasticsearch.search.SearchHits;
import org.lobid.resources.run.WikidataGeodata2Es;
import org.mortbay.log.Log;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.collect.SortedSetMultimap;
import com.google.common.collect.TreeMultimap;
import com.google.common.io.CharStreams;

/**
 * Index JSON into elasticsearch.
 * 
 * @author Pascal Christoph (dr0i)
 * @author Fabian Steeg (fsteeg)
 */
@In(HashMap.class)
@Out(Void.class)
public class ElasticsearchIndexer
		extends DefaultObjectPipe<HashMap<String, String>, ObjectReceiver<Void>> {

	private static final Logger LOG =
			LoggerFactory.getLogger(ElasticsearchIndexer.class);
	private String hostname;
	private String clustername;
	private BulkRequestBuilder bulkRequest;
	private InetSocketTransportAddress NODE;
	private TransportClient tc;
	private UpdateRequest updateRequest;
	private Client client;
	private int retries = 40;
	// collect so many documents before bulk indexing them all
	private int bulkSize = 5000;
	private int docs = 0;
	private String indexName;
	private boolean updateIndex;
	private String aliasSuffix = "";
	private static String indexConfig = "index-config.json";
	private static ObjectMapper mapper = new ObjectMapper();
	// TODDO setter?
	public static double MINIMUM_SCORE = 4.0;

	/**
	 * Keys to get index properties and the json document ("graph")
	 */
	@SuppressWarnings("javadoc")
	public static enum Properties {
		INDEX("_index"), TYPE("_type"), ID("_id"), PARENT("_parent"), GRAPH(
				"graph");
		private final String name;

		Properties(final String name) {
			this.name = name;
		}

		public String getName() {
			return name;
		}
	}

	@Override
	public void onCloseStream() {
		bulkRequest.setRefresh(true).get();
		// remove old and unprotected indices
		if (!aliasSuffix.equals("NOALIAS") && !updateIndex
				&& !aliasSuffix.toLowerCase().contains("test"))
			updateAliases();
		// feed the rest of the bulk
		if (bulkRequest.numberOfActions() != 0)
			bulkRequest.execute().actionGet();
	}

	// TODO use BulkProcessorbuilder by updating to ES 1.5
	@Override
	public void onSetReceiver() {
		if (client == null) {
			Settings settings = Settings.settingsBuilder()
					.put("cluster.name", this.clustername)
					.put("client.transport.sniff", false)
					.put("client.transport.ping_timeout", 120, TimeUnit.SECONDS).build();
			try {
				this.NODE = new InetSocketTransportAddress(
						InetAddress.getByName(this.hostname), 9300);
			} catch (UnknownHostException e) {
				e.printStackTrace();
			}
			this.tc = TransportClient.builder().settings(settings).build();
			this.client = this.tc.addTransportAddress(this.NODE);
		}
		bulkRequest = client.prepareBulk();
		if (updateIndex) {
			getNewestIndex();
		} else
			createIndex();
		bulkRequest.setRefresh(false);
		LOG.info(
				"Threshold minimum score for spatial enrichment: " + MINIMUM_SCORE);
	}

	@Override
	public void process(final HashMap<String, String> json) {
		LOG.debug("Try to index " + json.get(Properties.ID.getName())
				+ " in ES type " + json.get(Properties.TYPE.getName()) + " Source:"
				+ json.get(Properties.GRAPH.getName()));
		updateRequest = new UpdateRequest(indexName,
				json.get(Properties.TYPE.getName()), json.get(Properties.ID.getName()));
		String jsonDoc = json.get(Properties.GRAPH.getName());
		if (json.containsKey(Properties.PARENT.getName())) {
			updateRequest.parent(json.get(Properties.PARENT.getName()));
		} else {
			if (WikidataGeodata2Es.getIndexExists()) {
				try {
					ObjectNode node = mapper.readValue(
							json.get(Properties.GRAPH.getName()), ObjectNode.class);
					jsonDoc = enrich(WikidataGeodata2Es.getIndexAlias(), "coverage",
							"spatial", node);
				} catch (IOException e1) {
					LOG.info(
							"Enrichment problem with" + json.get(Properties.ID.getName()),
							e1);
				}
			}
		}
		updateRequest.doc(jsonDoc);
		updateRequest.docAsUpsert(true);
		bulkRequest.add(updateRequest);
		docs++;
		while (docs > bulkSize && retries > 0) {
			try {
				bulkRequest.execute().actionGet();
				docs = 0;
				bulkRequest = client.prepareBulk();
				bulkRequest.setRefresh(false);
				break; // stop retry-while
			} catch (final NoNodeAvailableException e) {
				retries--;
				try {
					Thread.sleep(10000);
				} catch (final InterruptedException x) {
					x.printStackTrace();
				}
				LOG.warn("Retry indexing record" + json.get(Properties.ID.getName())
						+ ":" + e.getMessage() + " (" + retries + " more retries)");
			}
		}
	}

	private String enrich(final String index, final String queryField,
			final String SPATIAL, ObjectNode node) {
		String[] query;
		Iterable<Entry<String, JsonNode>> iterable = () -> node.fields();
		Optional<Entry<String, JsonNode>> o =
				StreamSupport.stream(iterable.spliterator(), false)
						.filter(k -> k.getKey().equals(queryField)).findFirst();
		if (o.isPresent()) {
			query = o.get().getValue().toString().split("\",\"");
			ArrayNode spatialNode = node.putArray(SPATIAL);
			for (int i = 0; i < query.length; i++) {
				query[i] = query[i].replaceAll("[^\\p{IsAlphabetic}]", " ");
				MultiMatchQueryBuilder qsq = new MultiMatchQueryBuilder(query[i],
						SPATIAL + ".label^5", "aliases.de.value");
				SearchHits hits = null;
				try {
					hits = client.prepareSearch(index).setQuery(qsq).get().getHits();
					JsonNode newSpatialNode;
					if (hits.getTotalHits() > 0) {
						ObjectNode source = mapper.readValue(
								hits.getAt(0).getSource().get(SPATIAL).toString(),
								ObjectNode.class);
						LOG.info(i + " 1.Hit Query=" + query[i] + " score="
								+ hits.getAt(0).getScore() + " source="
								+ hits.getAt(0).getSource().get("spatial"));
						if (hits.getAt(0).getScore() < MINIMUM_SCORE) {
							LOG.info("Score " + hits.getAt(0).getScore() + " to low. Queried "
									+ query[i]);
							newSpatialNode = fallbackQuery(query[i]);
						} else
							newSpatialNode = source.get(SPATIAL);
						if (newSpatialNode != null)
							spatialNode.add(newSpatialNode);
					} else {
						newSpatialNode = fallbackQuery(query[i]);
						if (newSpatialNode != null)
							spatialNode.add(newSpatialNode);
					}
				} catch (Exception e) {
					LOG.warn("Couldn't get a hit using index '" + index + "' querying '"
							+ query[i] + "'", e.getLocalizedMessage());
				}
			}
			if (spatialNode.size() > 0)
				node.set(SPATIAL, spatialNode);
			else
				node.remove(SPATIAL);
		}
		return node.toString();
	}

	private static JsonNode fallbackQuery(final String QUERY) {
		LOG.info(
				"Fallback - querying https://www.wikidata.org...&srsearch=" + QUERY);
		JsonNode jn = WikidataGeodata2Es
				.extractEntitiesFromWikidataApiQueryAndTranformThemAndIndex2Es(
						"https://www.wikidata.org/w/api.php?action=query&list=search&format=json&srsearch="
								+ QUERY)
				.get("spatial");
		if (jn != null)
			Log.info("Fallback success: " + QUERY + "!");
		return jn;
	}

	/**
	 * Sets the name of the index config json filename.
	 * 
	 * @param indexConfig the filename of the index config
	 */
	public void setIndexConfig(final String indexConfig) {
		ElasticsearchIndexer.indexConfig = indexConfig;
	}

	/**
	 * Sets the elasticsearch cluster name.
	 * 
	 * @param clustername the name of the cluster
	 */
	public void setClustername(final String clustername) {
		this.clustername = clustername;
	}

	/**
	 * Sets the elasticsearch hostname
	 * 
	 * @param hostname may be an IP or a domain name
	 */
	public void setHostname(final String hostname) {
		this.hostname = hostname;
	}

	/**
	 * Sets the elasticsearch index name
	 * 
	 * @param indexname name of the index
	 */
	public void setIndexName(final String indexname) {
		this.indexName = indexname;
	}

	/**
	 * Sets the suffix of elasticsearch index alias suffix
	 * 
	 * @param aliasSuffix musn't have '-' in it
	 */
	public void setIndexAliasSuffix(String aliasSuffix) {
		this.aliasSuffix = aliasSuffix;
	}

	/**
	 * Sets the elasticsearch client.
	 * 
	 * @param client the elasticsearch client
	 */
	public void setElasticsearchClient(Client client) {
		this.client = client;
	}

	/**
	 * Sets the elasticsearch client.
	 * 
	 * @return client the elasticsearch client
	 * 
	 */
	public Client getElasticsearchClient() {
		return this.client;
	}

	/**
	 * Sets a flag wether the index alias(es) should be updated
	 * 
	 * @param updateIndex name of the index
	 */
	public void setUpdateNewestIndex(final boolean updateIndex) {
		this.updateIndex = updateIndex;
	}

	private void getNewestIndex() {
		String indexNameWithoutTimestamp = indexName.replaceAll("20.*", "");
		final SortedSetMultimap<String, String> indices =
				groupByIndexCollection(indexName);
		for (String prefix : indices.keySet()) {
			final SortedSet<String> indicesForPrefix = indices.get(prefix);
			final String newestIndex = indicesForPrefix.last();
			if (newestIndex.startsWith(indexNameWithoutTimestamp))
				indexName = newestIndex;
		}
		LOG.info("Going to UPDATE existing index " + indexName);
	}

	private void createIndex() {
		IndicesAdminClient adminClient = client.admin().indices();
		if (!adminClient.prepareExists(indexName).execute().actionGet()
				.isExists()) {
			LOG.info("Going to CREATE new index " + indexName + " at cluster "
					+ this.client.settings().get("cluster.name"));
			adminClient.prepareCreate(indexName).setSource(config()).execute()
					.actionGet();
		} else
			LOG.info("Index already exists, going to UPDATE index " + indexName);
	}

	private static String config() {
		String res = null;
		try {
			final InputStream config = Thread.currentThread().getContextClassLoader()
					.getResourceAsStream(indexConfig);
			try (InputStreamReader reader = new InputStreamReader(config, "UTF-8")) {
				res = CharStreams.toString(reader);
			}
		} catch (IOException e) {
			LOG.error(e.getMessage(), e);
		}
		return res;
	}

	/**
	 * Updates alias, may remove old indices.
	 * 
	 */
	public void updateAliases() {
		final SortedSetMultimap<String, String> indices =
				groupByIndexCollection(indexName);
		for (String prefix : indices.keySet()) {
			final SortedSet<String> indicesForPrefix = indices.get(prefix);
			final String newIndex = indicesForPrefix.last();
			final String newAlias = prefix + aliasSuffix;
			LOG.info("Prefix " + prefix + ", newest index: " + newIndex);
			removeOldAliases(indicesForPrefix, newAlias);
			if (!indexName.equals(newAlias) && !newIndex.equals(newAlias))
				createNewAlias(newIndex, newAlias);
			deleteOldIndices(indexName, indicesForPrefix);
		}
	}

	private SortedSetMultimap<String, String> groupByIndexCollection(
			final String name) {
		final SortedSetMultimap<String, String> indices = TreeMultimap.create();
		for (String index : client.admin().indices().prepareStats().execute()
				.actionGet().getIndices().keySet()) {
			final String[] nameAndTimestamp = index.split("-(?=\\d)");
			if (name.startsWith(nameAndTimestamp[0]))
				indices.put(nameAndTimestamp[0], index);
		}
		return indices;
	}

	private void removeOldAliases(final SortedSet<String> indicesForPrefix,
			final String newAlias) {
		try {
			for (String name : indicesForPrefix) {
				final Set<String> aliases = aliases(name);
				for (String alias : aliases) {
					if (alias.equals(newAlias)) {
						LOG.info("Delete alias index,alias: " + name + "," + alias);
						client.admin().indices().prepareAliases().removeAlias(name, alias)
								.execute().actionGet();
					}
				}
			}
		} catch (AliasesNotFoundException e) {
			LOG.warn("Alias not found", e);
		}
	}

	private void createNewAlias(final String newIndex, final String newAlias) {
		LOG.info("Create alias index,alias: " + newIndex + "," + newAlias);
		client.admin().indices().prepareAliases().addAlias(newIndex, newAlias)
				.execute().actionGet();
	}

	private void deleteOldIndices(final String name,
			final SortedSet<String> allIndices) {
		if (allIndices.size() >= 3) {
			final List<String> list = new ArrayList<>(allIndices);
			list.remove(name);
			for (String indexToDelete : list) {
				boolean hasAlias = client.admin().cluster()
						.state(Requests.clusterStateRequest().nodes(true)
								.indices(indexToDelete))
						.actionGet().getState().getMetaData()
						.hasAliases(new String[] { "*" }, new String[] { indexToDelete });
				if (!hasAlias) {
					client.admin().indices().delete(new DeleteIndexRequest(indexToDelete))
							.actionGet();
					LOG.info("Deleting index: " + indexToDelete);
				}
			}
		}
	}

	private Set<String> aliases(final String name) {
		final ClusterStateRequest clusterStateRequest =
				Requests.clusterStateRequest().nodes(true).indices(name);
		return client.admin().cluster().state(clusterStateRequest).actionGet()
				.getState().getMetaData().getAliasAndIndexLookup().keySet();
	}

}
