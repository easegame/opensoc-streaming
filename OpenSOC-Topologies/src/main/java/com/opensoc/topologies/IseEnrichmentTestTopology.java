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

package com.opensoc.topologies;

import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.Map;

import org.apache.commons.configuration.Configuration;
import org.apache.commons.configuration.PropertiesConfiguration;
import org.apache.storm.hdfs.bolt.HdfsBolt;
import org.apache.storm.hdfs.bolt.format.DefaultFileNameFormat;
import org.apache.storm.hdfs.bolt.format.DelimitedRecordFormat;
import org.apache.storm.hdfs.bolt.format.FileNameFormat;
import org.apache.storm.hdfs.bolt.format.RecordFormat;
import org.apache.storm.hdfs.bolt.rotation.FileRotationPolicy;
import org.apache.storm.hdfs.bolt.rotation.FileSizeRotationPolicy;
import org.apache.storm.hdfs.bolt.rotation.FileSizeRotationPolicy.Units;
import org.apache.storm.hdfs.bolt.sync.CountSyncPolicy;
import org.apache.storm.hdfs.bolt.sync.SyncPolicy;
import org.json.simple.JSONObject;

import storm.kafka.BrokerHosts;
import storm.kafka.KafkaSpout;
import storm.kafka.SpoutConfig;
import storm.kafka.StringScheme;
import storm.kafka.ZkHosts;
import storm.kafka.bolt.KafkaBolt;
import backtype.storm.Config;
import backtype.storm.LocalCluster;
import backtype.storm.StormSubmitter;
import backtype.storm.spout.RawScheme;
import backtype.storm.spout.SchemeAsMultiScheme;
import backtype.storm.topology.TopologyBuilder;

import com.opensoc.alerts.interfaces.TaggerAdapter;
import com.opensoc.enrichment.adapters.cif.CIFHbaseAdapter;
import com.opensoc.enrichment.adapters.whois.WhoisHBaseAdapter;
import com.opensoc.enrichment.common.GenericEnrichmentBolt;
import com.opensoc.enrichment.interfaces.EnrichmentAdapter;
import com.opensoc.enrichment.adapters.geo.GeoMysqlAdapter;
import com.opensoc.enrichment.adapters.host.HostFromPropertiesFileAdapter;
import com.opensoc.filters.GenericMessageFilter;
import com.opensoc.indexing.TelemetryIndexingBolt;
import com.opensoc.indexing.adapters.ESBaseBulkAdapter;
import com.opensoc.json.serialization.JSONKryoSerializer;
import com.opensoc.parsing.AbstractParserBolt;
import com.opensoc.parsing.TelemetryParserBolt;
import com.opensoc.parsing.parsers.BasicIseParser;
import com.opensoc.tagging.TelemetryTaggerBolt;
import com.opensoc.tagging.adapters.RegexTagger;
import com.opensoc.test.spouts.GenericInternalTestSpout;
import com.opensoc.topologyhelpers.SettingsLoader;

/**
 * This is a basic example of a Storm topology.
 */

public class IseEnrichmentTestTopology {
	static Configuration config;
	static TopologyBuilder builder;
	static String component = "Spout";
	static Config conf;
	static String subdir = "ise";

	public static void main(String[] args) throws Exception {

		String config_path = ".";
		boolean success = true;

		try {
			config_path = args[0];
		} catch (Exception e) {
			config_path = "OpenSOC_Configs";
		}

		String topology_conf_path = config_path + "/topologies/" + subdir + "/topology.conf";
		String environment_identifier_path = config_path + "/topologies/environment_identifier.conf";
		String topology_identifier_path =  config_path + "/topologies/" + subdir + "/topology_identifier.conf";
		
		System.out.println("[OpenSOC] Looking for environment identifier: " + environment_identifier_path);
		System.out.println("[OpenSOC] Looking for topology identifier: " + topology_identifier_path);
		System.out.println("[OpenSOC] Looking for topology config: " + topology_conf_path);
		
		config = new PropertiesConfiguration(topology_conf_path);

		JSONObject environment_identifier = SettingsLoader
				.loadEnvironmentIdnetifier(environment_identifier_path);
		JSONObject topology_identifier = SettingsLoader
				.loadTopologyIdnetifier(topology_identifier_path);

		String topology_name = SettingsLoader.generateTopologyName(
				environment_identifier, topology_identifier);

		System.out.println("[OpenSOC] Initializing Topology: " + topology_name);

		builder = new TopologyBuilder();

		conf = new Config();
		conf.registerSerialization(JSONObject.class, JSONKryoSerializer.class);
		conf.setDebug(config.getBoolean("debug.mode"));

		if (config.getBoolean("spout.test.enabled", false)) {
			String component_name = config.getString("spout.test.name",
					"DefaultTopologySpout");
			success = initializeTestingSpout("SampleInput/ISESampleOutput",
					component_name);
			component = component_name;

			System.out.println("[OpenSOC] Component " + component
					+ " initialized");
		}

		if (config.getBoolean("spout.kafka.enabled", true)) {
			String component_name = config.getString("spout.kafka.name",
					"DefaultTopologyKafkaSpout");
			success = initializeKafkaSpout(component_name);
			component = component_name;

			System.out.println("[OpenSOC] Component " + component
					+ " initialized");
		}

		if (config.getBoolean("parser.bolt.enabled", true)) {
			String component_name = config.getString("parser.bolt.name",
					"DefaultTopologyParserBot");
			success = initializeParsingBolt(topology_name, component_name);
			component = component_name;

			System.out.println("[OpenSOC] Component " + component
					+ " initialized");
		}

		if (config.getBoolean("bolt.enrichment.geo.enabled", false)) {
			String component_name = config.getString(
					"bolt.enrichment.geo.name", "DefaultGeoEnrichmentBolt");
			success = initializeGeoEnrichment(topology_name, component_name);
			component = component_name;

			System.out.println("[OpenSOC] Component " + component
					+ " initialized");
		}

		if (config.getBoolean("bolt.enrichment.host.enabled", false)) {
			String component_name = config.getString(
					"bolt.enrichment.host.name", "DefaultHostEnrichmentBolt");
			success = initializeHostsEnrichment(topology_name, component_name,
					"OpenSOC_Configs/etc/whitelists/known_hosts.conf");
			component = component_name;

			System.out.println("[OpenSOC] Component " + component
					+ " initialized");
		}

		if (config.getBoolean("bolt.enrichment.whois.enabled", false)) {
			String component_name = config.getString(
					"bolt.enrichment.whois.name", "DefaultWhoisEnrichmentBolt");
			success = initializeWhoisEnrichment(topology_name, component_name);
			component = component_name;

			System.out.println("[OpenSOC] Component " + component
					+ " initialized");
		}

		if (config.getBoolean("bolt.enrichment.cif.enabled", false)) {
			String component_name = config.getString(
					"bolt.enrichment.cif.name", "DefaultCIFEnrichmentBolt");
			success = initializeCIFEnrichment(topology_name, component_name);
			component = component_name;

			System.out.println("[OpenSOC] Component " + component
					+ " initialized");
		}

		if (config.getBoolean("bolt.alerts.enabled", false)) {
			String component_name = config.getString("bolt.alerts.name",
					"DefaultAlertsBolt");
			success = initializeAlerts(topology_name, component_name,
					config_path + "/topologies/" + subdir + "/alerts.xml",
					environment_identifier, topology_identifier);
			component = component_name;

			System.out.println("[OpenSOC] Component " + component
					+ " initialized");
		}

		if (config.getBoolean("bolt.kafka.enabled", false)) {
			String component_name = config.getString("bolt.kafka.name",
					"DefaultKafkaBolt");
			success = initializeKafkaBolt(component_name);

			System.out.println("[OpenSOC] Component " + component_name
					+ " initialized");
		}

		if (config.getBoolean("bolt.indexing.enabled", true)) {
			String component_name = config.getString("bolt.indexing.name",
					"DefaultIndexingBolt");
			success = initializeIndexingBolt(component_name);

			System.out.println("[OpenSOC] Component " + component_name
					+ " initialized");
		}

		if (config.getBoolean("bolt.hdfs.enabled", false)) {
			String component_name = config.getString("bolt.hdfs.name",
					"DefaultHDFSBolt");
			success = initializeHDFSBolt(topology_name, component_name);

			System.out.println("[OpenSOC] Component " + component_name
					+ " initialized");
		}

		if (config.getBoolean("local.mode")) {
			conf.setNumWorkers(config.getInt("num.workers"));
			conf.setMaxTaskParallelism(1);
			LocalCluster cluster = new LocalCluster();
			cluster.submitTopology(topology_name, conf,
					builder.createTopology());
		} else {

			conf.setNumWorkers(config.getInt("num.workers"));
			StormSubmitter.submitTopology(topology_name, conf,
					builder.createTopology());
		}
	}

	public static boolean initializeKafkaSpout(String name) {
		try {

			BrokerHosts zk = new ZkHosts(config.getString("kafka.zk"));
			String input_topic = config.getString("spout.kafka.topic");
			SpoutConfig kafkaConfig = new SpoutConfig(zk, input_topic, "",
					input_topic);
			kafkaConfig.scheme = new SchemeAsMultiScheme(new RawScheme());
			// kafkaConfig.forceFromStart = Boolean.valueOf("True");
			kafkaConfig.startOffsetTime = -1;

			builder.setSpout(name, new KafkaSpout(kafkaConfig),
					config.getInt("spout.kafka.parallelism.hint")).setNumTasks(
					config.getInt("spout.kafka.num.tasks"));

		} catch (Exception e) {
			e.printStackTrace();
			System.exit(0);
		}

		return true;
	}

	public static boolean initializeTestingSpout(String file_path, String name) {
		try {

			GenericInternalTestSpout testSpout = new GenericInternalTestSpout()
					.withFilename(file_path).withRepeating(config.getBoolean("spout.test.parallelism.repeat", false));

			builder.setSpout(name, testSpout,
					config.getInt("spout.test.parallelism.hint")).setNumTasks(
					config.getInt("spout.test.num.tasks"));

		} catch (Exception e) {
			e.printStackTrace();
			System.exit(0);
		}
		return true;
	}

	public static boolean initializeParsingBolt(String topology_name,
			String name) {
		try {

			AbstractParserBolt parser_bolt = new TelemetryParserBolt()
					.withMessageParser(new BasicIseParser())
					.withOutputFieldName(topology_name)
					.withMessageFilter(new GenericMessageFilter())
					.withMetricConfig(config);

			builder.setBolt(name, parser_bolt,
					config.getInt("bolt.parser.parallelism.hint"))
					.shuffleGrouping(component)
					.setNumTasks(config.getInt("bolt.parser.num.tasks"));

		} catch (Exception e) {
			e.printStackTrace();
			System.exit(0);
		}

		return true;
	}

	public static boolean initializeGeoEnrichment(String topology_name,
			String name) {

		try {
			List<String> geo_keys = new ArrayList<String>();
			geo_keys.add(config.getString("source.ip"));
			geo_keys.add(config.getString("dest.ip"));

			GeoMysqlAdapter geo_adapter = new GeoMysqlAdapter(
					config.getString("mysql.ip"), config.getInt("mysql.port"),
					config.getString("mysql.username"),
					config.getString("mysql.password"),
					config.getString("bolt.enrichment.geo.adapter.table"));

			GenericEnrichmentBolt geo_enrichment = new GenericEnrichmentBolt()
					.withEnrichmentTag(
							config.getString("bolt.enrichment.geo.enrichment_tag"))
					.withOutputFieldName(topology_name)
					.withAdapter(geo_adapter)
					.withMaxTimeRetain(
							config.getInt("bolt.enrichment.geo.MAX_TIME_RETAIN"))
					.withMaxCacheSize(
							config.getInt("bolt.enrichment.geo.MAX_CACHE_SIZE"))
					.withKeys(geo_keys).withMetricConfiguration(config);

			builder.setBolt(name, geo_enrichment,
					config.getInt("bolt.enrichment.geo.parallelism.hint"))
					.shuffleGrouping(component)
					.setNumTasks(config.getInt("bolt.enrichment.geo.num.tasks"));

		} catch (Exception e) {
			e.printStackTrace();
			System.exit(0);
		}

		return true;
	}

	public static boolean initializeHostsEnrichment(String topology_name,
			String name, String hosts_path) {

		try {
			List<String> hosts_keys = new ArrayList<String>();
			hosts_keys.add(config.getString("source.ip"));
			hosts_keys.add(config.getString("dest.ip"));

			Map<String, JSONObject> known_hosts = SettingsLoader
					.loadKnownHosts(hosts_path);

			HostFromPropertiesFileAdapter host_adapter = new HostFromPropertiesFileAdapter(known_hosts);

			GenericEnrichmentBolt host_enrichment = new GenericEnrichmentBolt()
					.withEnrichmentTag(
							config.getString("bolt.enrichment.host.enrichment_tag"))
					.withAdapter(host_adapter)
					.withMaxTimeRetain(
							config.getInt("bolt.enrichment.host.MAX_TIME_RETAIN"))
					.withMaxCacheSize(
							config.getInt("bolt.enrichment.host.MAX_CACHE_SIZE"))
					.withOutputFieldName(topology_name).withKeys(hosts_keys)
					.withMetricConfiguration(config);

			builder.setBolt(name, host_enrichment,
					config.getInt("bolt.enrichment.host.parallelism.hint"))
					.shuffleGrouping(component)
					.setNumTasks(
							config.getInt("bolt.enrichment.host.num.tasks"));

		} catch (Exception e) {
			e.printStackTrace();
			System.exit(0);
		}

		return true;
	}

	public static boolean initializeAlerts(String topology_name, String name,
			String alerts_path, JSONObject environment_identifier,
			JSONObject topology_identifier) {
		try {

			JSONObject alerts_identifier = SettingsLoader
					.generateAlertsIdentifier(environment_identifier,
							topology_identifier);
			Map<String, JSONObject> rules = SettingsLoader
					.loadRegexAlerts(alerts_path);

			TaggerAdapter tagger_adapter = new RegexTagger(rules);

			TelemetryTaggerBolt alerts_bolt = new TelemetryTaggerBolt()
					.withIdentifier(alerts_identifier)
					.withMessageTagger(tagger_adapter)
					.withOutputFieldName("message");

			builder.setBolt(name, alerts_bolt,
					config.getInt("bolt.alerts.parallelism.hint"))
					.shuffleGrouping(component)
					.setNumTasks(config.getInt("bolt.alerts.num.tasks"));

		} catch (Exception e) {
			e.printStackTrace();
			System.exit(0);
		}
		return true;
	}

	public static boolean initializeKafkaBolt(String name) {
		try {

			Map<String, String> kafka_broker_properties = new HashMap<String, String>();
			kafka_broker_properties.put("zk.connect",
					config.getString("kafka.zk"));
			kafka_broker_properties.put("metadata.broker.list",
					config.getString("kafka.br"));

			kafka_broker_properties.put("serializer.class",
					"com.opensoc.json.serialization.JSONKafkaSerializer");

			String output_topic = config.getString("bolt.kafka.topic");

			conf.put("kafka.broker.properties", kafka_broker_properties);
			conf.put("topic", output_topic);

			builder.setBolt(name, new KafkaBolt<String, String>(),
					config.getInt("bolt.kafka.parallelism.hint"))
					.shuffleGrouping(component)
					.setNumTasks(config.getInt("bolt.kafka.num.tasks"));
		} catch (Exception e) {
			e.printStackTrace();
			System.exit(0);
		}
		return true;
	}

	public static boolean initializeWhoisEnrichment(String topology_name,
			String name) {
		try {

			List<String> whois_keys = new ArrayList<String>();
			String[] keys_from_settings = config.getString(
					"bolt.enrichment.whois.source").split(",");

			for (String key : keys_from_settings)
				whois_keys.add(key);

			EnrichmentAdapter whois_adapter = new WhoisHBaseAdapter(
					config.getString("bolt.enrichment.whois.hbase.table.name"),
					config.getString("kafka.zk.list"),
					config.getString("kafka.zk.port"));

			GenericEnrichmentBolt whois_enrichment = new GenericEnrichmentBolt()
					.withEnrichmentTag(
							config.getString("bolt.enrichment.whois.enrichment_tag"))
					.withOutputFieldName(topology_name)
					.withAdapter(whois_adapter)
					.withMaxTimeRetain(
							config.getInt("bolt.enrichment.whois.MAX_TIME_RETAIN"))
					.withMaxCacheSize(
							config.getInt("bolt.enrichment.whois.MAX_CACHE_SIZE"))
					.withKeys(whois_keys).withMetricConfiguration(config);

			builder.setBolt(name, whois_enrichment,
					config.getInt("bolt.enrichment.whois.parallelism.hint"))
					.shuffleGrouping(component)
					.setNumTasks(
							config.getInt("bolt.enrichment.whois.num.tasks"));

		} catch (Exception e) {
			e.printStackTrace();
			System.exit(0);
		}

		return true;
	}

	public static boolean initializeIndexingBolt(String name) {
		try {

			TelemetryIndexingBolt indexing_bolt = new TelemetryIndexingBolt()
					.withIndexIP(config.getString("es.ip"))
					.withIndexPort(config.getInt("es.port"))
					.withClusterName(config.getString("es.clustername"))
					.withIndexName(config.getString("bolt.indexing.indexname"))
					.withDocumentName(
							config.getString("bolt.indexing.documentname"))
					.withBulk(config.getInt("bolt.indexing.bulk"))
					.withIndexAdapter(new ESBaseBulkAdapter())
					.withMetricConfiguration(config);

			builder.setBolt(name, indexing_bolt,
					config.getInt("bolt.indexing.parallelism.hint"))
					.shuffleGrouping(component)
					.setNumTasks(config.getInt("bolt.indexing.num.tasks"));

		} catch (Exception e) {
			e.printStackTrace();
			System.exit(0);
		}

		return true;
	}

	public static boolean initializeCIFEnrichment(String topology_name,
			String name) {
		try {

			List<String> cif_keys = new ArrayList<String>();

			cif_keys.add(config.getString("source.ip"));
			cif_keys.add(config.getString("dest.ip"));
			cif_keys.add(config.getString("bolt.enrichment.cif.host"));
			cif_keys.add(config.getString("bolt.enrichment.cif.email"));

			GenericEnrichmentBolt cif_enrichment = new GenericEnrichmentBolt()
					.withEnrichmentTag(
							config.getString("bolt.enrichment.cif.enrichment_tag"))
					.withAdapter(
							new CIFHbaseAdapter(config
									.getString("kafka.zk.list"), config
									.getString("kafka.zk.port"), config
									.getString("bolt.enrichment.cif.tablename")))
					.withOutputFieldName(topology_name)
					.withEnrichmentTag("CIF_Enrichment")
					.withKeys(cif_keys)
					.withMaxTimeRetain(
							config.getInt("bolt.enrichment.cif.MAX_TIME_RETAIN"))
					.withMaxCacheSize(
							config.getInt("bolt.enrichment.cif.MAX_CACHE_SIZE"))
					.withMetricConfiguration(config);

			builder.setBolt(name, cif_enrichment,
					config.getInt("bolt.enrichment.cif.parallelism.hint"))
					.shuffleGrouping(component)
					.setNumTasks(config.getInt("bolt.enrichment.cif.num.tasks"));

		} catch (Exception e) {
			e.printStackTrace();
			System.exit(0);
		}

		return true;
	}

	public static boolean initializeHDFSBolt(String topology_name, String name) {
		try {

			// * ------------HDFS BOLT configuration

			/*
			 * FileNameFormat fileNameFormat = new DefaultFileNameFormat()
			 * .withPath("/" + topology_name + "/"); RecordFormat format = new
			 * DelimitedRecordFormat() .withFieldDelimiter("|");
			 * 
			 * SyncPolicy syncPolicy = new CountSyncPolicy(5);
			 * FileRotationPolicy rotationPolicy = new
			 * FileSizeRotationPolicy(config
			 * .getFloat("bolt.hdfs.size.rotation.policy" ), Units.KB);
			 * 
			 * HdfsBolt hdfsBolt = new
			 * HdfsBolt().withFsUrl(config.getString("bolt.hdfs.fs.url"))
			 * .withFileNameFormat(fileNameFormat).withRecordFormat(format)
			 * .withRotationPolicy(rotationPolicy).withSyncPolicy(syncPolicy);
			 * 
			 * builder.setBolt("HDFSBolt", hdfsBolt,
			 * config.getInt("bolt.hdfs.parallelism.hint"))
			 * .shuffleGrouping("CIFEnrichmentBolt"
			 * ).setNumTasks(config.getInt("bolt.hdfs.num.tasks"));
			 */

			// * ------------HDFS BOLT For Enriched Data configuration

			FileNameFormat fileNameFormat_enriched = new DefaultFileNameFormat()
					.withPath(config.getString("bolt.hdfs.path","/") + "/" +  topology_name + "_enriched/");
			RecordFormat format_enriched = new DelimitedRecordFormat()
					.withFieldDelimiter("|");

			SyncPolicy syncPolicy_enriched = new CountSyncPolicy(5);
			FileRotationPolicy rotationPolicy_enriched = new FileSizeRotationPolicy(
					config.getFloat("bolt.hdfs.size.rotation.policy"), Units.KB);

			HdfsBolt hdfsBolt_enriched = new HdfsBolt()
					.withFsUrl(config.getString("bolt.hdfs.fs.url"))
					.withFileNameFormat(fileNameFormat_enriched)
					.withRecordFormat(format_enriched)
					.withRotationPolicy(rotationPolicy_enriched)
					.withSyncPolicy(syncPolicy_enriched);

			builder.setBolt(name, hdfsBolt_enriched,
					config.getInt("bolt.hdfs.parallelism.hint"))
					.shuffleGrouping(component)
					.setNumTasks(config.getInt("bolt.hdfs.num.tasks"));

		} catch (Exception e) {
			e.printStackTrace();
			System.exit(0);
		}

		return true;
	}
}
