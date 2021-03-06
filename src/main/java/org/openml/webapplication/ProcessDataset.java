package org.openml.webapplication;

import java.io.BufferedReader;
import java.io.File;
import java.net.URL;
import java.util.List;

import org.openml.apiconnector.algorithms.Conversion;
import org.openml.apiconnector.algorithms.Input;
import org.openml.apiconnector.io.ApiException;
import org.openml.apiconnector.io.OpenmlConnector;
import org.openml.apiconnector.xml.DataFeature;
import org.openml.apiconnector.xml.DataFeature.Feature;
import org.openml.apiconnector.xml.DataFeatureUpload;
import org.openml.apiconnector.xml.DataSetDescription;
import org.openml.apiconnector.xml.DataUnprocessed;
import org.openml.apiconnector.xstream.XstreamXmlMapping;
import org.openml.webapplication.features.CharacterizerFactory;
import org.openml.webapplication.features.ExtractFeatures;
import org.openml.webapplication.features.FantailConnector;
import org.openml.webapplication.settings.Settings;

import weka.core.Instances;

import com.thoughtworks.xstream.XStream;

public class ProcessDataset {

	private final OpenmlConnector apiconnector;
	private final XStream xstream;
	
	public ProcessDataset(OpenmlConnector ac, String mode) throws Exception {
		this(ac, null, mode);
	}
	
	public ProcessDataset(OpenmlConnector connector, Integer dataset_id, String mode) throws Exception {
		apiconnector = connector;
		xstream = XstreamXmlMapping.getInstance();
		if(dataset_id != null) {
			Conversion.log( "OK", "Process Dataset", "Processing dataset " + dataset_id + " on special request. ");
			process(dataset_id);
		} else {
			DataUnprocessed du = connector.dataUnprocessed(Settings.EVALUATION_ENGINE_ID, mode);
			
			while(du != null) {
				dataset_id = du.getDatasets()[0].getDid();
				Conversion.log("OK", "Process Dataset", "Processing dataset " + dataset_id + " as obtained from database. ");
				process( dataset_id );
				du = connector.dataUnprocessed(Settings.EVALUATION_ENGINE_ID, mode);
			}
			Conversion.log("OK", "Process Dataset", "No more datasets to process. ");
		}
	}
	
	public void process(Integer did) throws Exception {

		DataSetDescription dsd = apiconnector.dataGet(did);
		URL datasetURL = apiconnector.getOpenmlFileUrl(dsd.getFile_id(), dsd.getName() + "." + dsd.getFormat());
		String defaultTarget = dsd.getDefault_target_attribute();
		
		try {
			FantailConnector fantail = new FantailConnector(apiconnector, CharacterizerFactory.simple());
			Instances dataset = new Instances(new BufferedReader(Input.getURL(datasetURL)));
			Conversion.log( "OK", "Process Dataset", "Processing dataset " + did + " - obtaining features. " );
			List<Feature> features = ExtractFeatures.getFeatures(dataset,defaultTarget);
			DataFeature datafeature = new DataFeature(did, Settings.EVALUATION_ENGINE_ID, features.toArray(new Feature[features.size()]));
			File dataFeatureFile = Conversion.stringToTempFile(xstream.toXML(datafeature), "features-did" + did, "xml");
			DataFeatureUpload dfu = apiconnector.dataFeaturesUpload(dataFeatureFile);
			
			Conversion.log( "OK", "Process Dataset", "Processing dataset " + dfu.getDid() + " - obtaining basic qualities. " );
			fantail.computeMetafeatures(did);
			Conversion.log("OK", "Process Dataset", "Dataset " + did + " - Processed successfully. ");
		} catch(ApiException e) {
			if (e.getCode() == 431) {
				// dataset already processed
				Conversion.log("Notice", "Process Dataset", e.getMessage());
			} else {
				e.printStackTrace();
				processDatasetWithError(did, e.getMessage());
			}
		} catch(Exception e) {
			e.printStackTrace();
			processDatasetWithError(did, e.getMessage());
		} catch (OutOfMemoryError e) {
			e.printStackTrace();
			processDatasetWithError(did, e.getMessage());
		}
	}
	
	private void processDatasetWithError(int did, String errorMessage) throws Exception {
		Conversion.log("Error", "Process Dataset", "Error while processing dataset. Marking this in database.");
		DataFeature datafeature = new DataFeature(did, Settings.EVALUATION_ENGINE_ID, errorMessage);
		File dataFeatureFile = Conversion.stringToTempFile(xstream.toXML(datafeature), "features-error-did" + did, "xml");
		DataFeatureUpload dfu = apiconnector.dataFeaturesUpload(dataFeatureFile);
		Conversion.log("Error", "Process Dataset", "Dataset " + dfu.getDid() + " - Error: " + errorMessage);
	}
}
