/*
 *  Webapplication - Java library that runs on OpenML servers
 *  Copyright (C) 2014 
 *  @author Jan N. van Rijn (j.n.van.rijn@liacs.leidenuniv.nl)
 *  
 *  This program is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *  
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *  
 *  You should have received a copy of the GNU General Public License
 *  along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *  
 */
package org.openml.webapplication.evaluate;

import java.io.BufferedReader;
import java.net.URL;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.openml.apiconnector.algorithms.Conversion;
import org.openml.apiconnector.algorithms.Input;
import org.openml.apiconnector.algorithms.MathHelper;
import org.openml.apiconnector.algorithms.TaskInformation;
import org.openml.apiconnector.io.OpenmlConnector;
import org.openml.apiconnector.models.MetricScore;
import org.openml.apiconnector.xml.DataSetDescription;
import org.openml.apiconnector.xml.EvaluationScore;
import org.openml.apiconnector.xml.Task;
import org.openml.apiconnector.xml.Task.Input.Estimation_procedure;
import org.openml.webapplication.algorithm.InstancesHelper;
import org.openml.webapplication.generatefolds.EstimationProcedure;
import org.openml.webapplication.io.Output;
import org.openml.webapplication.predictionCounter.FoldsPredictionCounter;
import org.openml.webapplication.predictionCounter.PredictionCounter;

import weka.classifiers.Evaluation;
import weka.core.Instance;
import weka.core.Instances;

public class EvaluateBatchPredictions implements PredictionEvaluator {

	private final int nrOfClasses;

	private final int ATT_PREDICTION_ROWID;
	private final int ATT_PREDICTION_FOLD;
	private final int ATT_PREDICTION_REPEAT;
	private final int ATT_PREDICTION_PREDICTION;
	private final int ATT_PREDICTION_SAMPLE;
	private final int[] ATT_PREDICTION_CONFIDENCE;

	private final Instances dataset;
	private final Instances splits;
	private final Instances predictions;

	private final PredictionCounter predictionCounter;
	private final String[] classes;
	private final TaskType taskType;
	private final double[][] cost_matrix;
	private final Evaluation[][][][] sampleEvaluation;
	private final boolean bootstrap;

	private EvaluationScore[] evaluationScores;

	public EvaluateBatchPredictions(OpenmlConnector openml, Task task, URL predictionsPath) throws Exception {
		final int datasetId = TaskInformation.getSourceData(task).getData_set_id();
		DataSetDescription dsd = openml.dataGet(datasetId);
		
		// set all arff files needed for this operation.
		URL datasetPath = openml.getOpenmlFileUrl(dsd.getFile_id(), dsd.getName());
		Conversion.log("OK", "EvaluateBatchPredictions", "dataset url: " + datasetPath);
		dataset = new Instances(new BufferedReader(Input.getURL(datasetPath))); 
		// TODO: we could use openml.getFileFromURL for more robust arff handling
		
		URL splitsPath = TaskInformation.getEstimationProcedure(task).getData_splits_url();
		Conversion.log("OK", "EvaluateBatchPredictions", "splits url : " + splitsPath);
		splits = new Instances(new BufferedReader(Input.getURL(splitsPath)));
		// TODO: we could use openml.getFileFromURL for more robust
		
		predictions = new Instances(new BufferedReader(Input.getURL(predictionsPath)));
		Conversion.log("OK", "EvaluateBatchPredictions", "predictions: " + predictionsPath);
		
		Estimation_procedure estimationprocedure = TaskInformation.getEstimationProcedure(task);
		this.bootstrap = estimationprocedure.getType().equals(EstimationProcedure.estimationProceduresTxt[6] );
		String classAttribute = TaskInformation.getSourceData(task).getTarget_feature();
		cost_matrix = TaskInformation.getCostMatrix(task);

		// Set class attribute to dataset ...
		if (dataset.attribute(classAttribute) != null) {
			dataset.setClass(dataset.attribute(classAttribute));
		} else {
			throw new RuntimeException("Class attribute (" + classAttribute + ") not found");
		}

		// ... and specify which task we are doing. classification or
		// regression.
		if (dataset.classAttribute().isNominal()) {
			if (predictions.attribute("sample") == null) {
				taskType = TaskType.CLASSIFICATION;
			} else {
				taskType = TaskType.LEARNINGCURVE;
			}
		} else {
			taskType = TaskType.REGRESSION;
		}

		// initiate a class that will help us with checking the prediction
		// count.
		predictionCounter = new FoldsPredictionCounter(splits);
		sampleEvaluation = new Evaluation[predictionCounter.getRepeats()][predictionCounter
				.getFolds()][predictionCounter.getSamples()][bootstrap ? 2 : 1];

		// *** A sample is considered to be a subset of a fold. In a normal
		// n-times n-fold crossvalidation
		// setting, each fold consists of 1 sample. In a leaning curve example,
		// each fold could consist
		// of more samples.

		// register row indexes.
		ATT_PREDICTION_ROWID = InstancesHelper.getRowIndex("row_id", predictions);
		ATT_PREDICTION_REPEAT = InstancesHelper.getRowIndex(new String[] { "repeat", "repeat_nr" }, predictions);
		ATT_PREDICTION_FOLD = InstancesHelper.getRowIndex(new String[] { "fold", "fold_nr" }, predictions);
		ATT_PREDICTION_PREDICTION = InstancesHelper.getRowIndex(new String[] { "prediction" }, predictions);
		if (taskType == TaskType.LEARNINGCURVE) {
			ATT_PREDICTION_SAMPLE = InstancesHelper.getRowIndex(new String[] { "sample", "sample_nr" }, predictions);
		} else {
			ATT_PREDICTION_SAMPLE = -1;
		}
		// do the same for the confidence fields. This number is dependent on
		// the number
		// of classes in the data set, hence the for-loop.
		nrOfClasses = dataset.classAttribute().numValues(); // returns 0 if
															// numeric, that's
															// good.
		classes = new String[nrOfClasses];
		ATT_PREDICTION_CONFIDENCE = new int[nrOfClasses];
		for (int i = 0; i < classes.length; i++) {
			classes[i] = dataset.classAttribute().value(i);
			String attribute = "confidence." + classes[i];
			if (predictions.attribute(attribute) != null) {
				ATT_PREDICTION_CONFIDENCE[i] = predictions.attribute(attribute).index();
			} else {
				throw new Exception("Attribute " + attribute + " not found among predictions. ");
			}
		}

		// and do the actual evaluation.
		doEvaluation();
	}

	private void doEvaluation() throws Exception {
		// set global evaluation
		Evaluation[] e = new Evaluation[bootstrap ? 2 : 1];

		for (int i = 0; i < e.length; ++i) {
			e[i] = new Evaluation(dataset);
			if (cost_matrix != null) {
				// TODO test
				e[i] = new Evaluation(dataset, InstancesHelper.doubleToCostMatrix(cost_matrix));
			} else {
				e[i] = new Evaluation(dataset);
			}
		}

		// set local evaluations
		for (int i = 0; i < sampleEvaluation.length; ++i) {
			for (int j = 0; j < sampleEvaluation[i].length; ++j) {
				for (int k = 0; k < sampleEvaluation[i][j].length; ++k) {
					for (int m = 0; m < (bootstrap ? 2 : 1); ++m) {
						if (cost_matrix != null) {
							// TODO test
							sampleEvaluation[i][j][k][m] = new Evaluation(dataset, InstancesHelper.doubleToCostMatrix(cost_matrix));
						} else {
							sampleEvaluation[i][j][k][m] = new Evaluation(dataset);
						}
					}
				}
			}
		}

		for (int i = 0; i < predictions.numInstances(); i++) {
			Instance prediction = predictions.instance(i);
			int repeat = ATT_PREDICTION_REPEAT < 0 ? 0 : (int) prediction.value(ATT_PREDICTION_REPEAT);
			int fold = ATT_PREDICTION_FOLD < 0 ? 0 : (int) prediction.value(ATT_PREDICTION_FOLD);
			int sample = ATT_PREDICTION_SAMPLE < 0 ? 0 : (int) prediction.value(ATT_PREDICTION_SAMPLE);
			int rowid = (int) prediction.value(ATT_PREDICTION_ROWID);

			predictionCounter.addPrediction(repeat, fold, sample, rowid);
			if (dataset.numInstances() <= rowid) {
				throw new RuntimeException("Making a prediction for row_id" + rowid
						+ " (0-based) while dataset has only " + dataset.numInstances() + " instances. ");
			}

			int bootstrap = 0;
			boolean measureGlobalScore = true;
			if (taskType == TaskType.LEARNINGCURVE && sample != predictionCounter.getSamples() - 1) {
				// for learning curves, we want the score of the last sample at
				// global score
				measureGlobalScore = false;
			}

			if (taskType == TaskType.REGRESSION) {
				if (measureGlobalScore) {
					e[bootstrap].evaluateModelOnce(prediction.value(ATT_PREDICTION_PREDICTION),
							dataset.instance(rowid));
				}
				sampleEvaluation[repeat][fold][sample][bootstrap].evaluateModelOnce(prediction.value(ATT_PREDICTION_PREDICTION), dataset.instance(rowid));
			} else {
				// TODO: catch error when no prob distribution is provided
				double[] confidences = InstancesHelper.predictionToConfidences(dataset, prediction, ATT_PREDICTION_CONFIDENCE, ATT_PREDICTION_PREDICTION);

				if (measureGlobalScore) {
					e[bootstrap].evaluateModelOnceAndRecordPrediction(confidences, dataset.instance(rowid));
				}
				sampleEvaluation[repeat][fold][sample][bootstrap].evaluateModelOnceAndRecordPrediction(confidences, dataset.instance(rowid));
			}
		}

		if (predictionCounter.check() == false) {
			throw new RuntimeException("Prediction count does not match: " + predictionCounter.getErrorMessage());
		}

		List<EvaluationScore> evaluationMeasuresList = new ArrayList<EvaluationScore>();
		Map<String, MetricScore> globalMeasures = Output.evaluatorToMap(e, nrOfClasses, taskType, bootstrap);
		for (String math_function : globalMeasures.keySet()) {
			MetricScore score = globalMeasures.get(math_function);
			// preventing divisions by zero and infinite scores (given by Weka)
			if (score.getScore() != null && score.getScore().isNaN() == false && score.getScore().isInfinite() == false) { 
				DecimalFormat dm = MathHelper.defaultDecimalFormat;
				EvaluationScore em = new EvaluationScore(math_function,
						score.getScore() == null ? null : dm.format(score.getScore()), null,
						score.getArrayAsString(dm));
				evaluationMeasuresList.add(em);

			}
		}
		for (int i = 0; i < sampleEvaluation.length; ++i) {
			for (int j = 0; j < sampleEvaluation[i].length; ++j) {
				for (int k = 0; k < sampleEvaluation[i][j].length; ++k) {
					Map<String, MetricScore> currentMeasures = Output.evaluatorToMap(sampleEvaluation[i][j][k],
							nrOfClasses, taskType, bootstrap);
					for (String math_function : currentMeasures.keySet()) {
						MetricScore score = currentMeasures.get(math_function);
						// preventing divisions by zero and infinite scores (given by Weka)
						if (score.getScore() != null && score.getScore().isNaN() == false && score.getScore().isInfinite() == false) { 
							DecimalFormat dm = MathHelper.defaultDecimalFormat;
							EvaluationScore currentMeasure;

							if (taskType == TaskType.LEARNINGCURVE) {
								currentMeasure = new EvaluationScore(math_function,
										score.getScore() == null ? null : dm.format(score.getScore()),
										score.getArrayAsString(dm), i, j, k,
										predictionCounter.getShadowTypeSize(i, j, k));
							} else {
								currentMeasure = new EvaluationScore(math_function,
										score.getScore() == null ? null : dm.format(score.getScore()),
										score.getArrayAsString(dm), i, j);
							}
							evaluationMeasuresList.add(currentMeasure);
						}
					}
				}
			}
		}
		evaluationScores = evaluationMeasuresList.toArray(new EvaluationScore[evaluationMeasuresList.size()]);
	}

	public EvaluationScore[] getEvaluationScores() {
		return evaluationScores;
	}

	@Override
	public PredictionCounter getPredictionCounter() {
		return predictionCounter;
	}

}