/**
 * @author Hang Su <hangsu@gatech.edu>.
 */

package edu.gatech.cse8803.main

import java.text.SimpleDateFormat

import edu.gatech.cse8803.clustering.{NMF, Metrics}
import edu.gatech.cse8803.features.FeatureConstruction
import edu.gatech.cse8803.ioutils.CSVUtils
import edu.gatech.cse8803.model.{Diagnostic, LabResult, Medication}
import edu.gatech.cse8803.phenotyping.T2dmPhenotype
import org.apache.spark.SparkContext._
import org.apache.spark.mllib.clustering.{GaussianMixture, KMeans}
import org.apache.spark.mllib.linalg.{DenseMatrix, Matrices, Vectors, Vector}
import org.apache.spark.mllib.feature.StandardScaler
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.SQLContext
import org.apache.spark.{SparkConf, SparkContext}

import scala.io.Source


object Main {
  def main(args: Array[String]) {
    import org.apache.log4j.Logger
    import org.apache.log4j.Level

    Logger.getLogger("org").setLevel(Level.WARN)
    Logger.getLogger("akka").setLevel(Level.WARN)
    

    val sc = createContext
    val sqlContext = new SQLContext(sc)

    /** initialize loading of data */
    val (medication, labResult, diagnostic) = loadRddRawData(sqlContext)
    val (candidateMedication, candidateLab, candidateDiagnostic) = loadLocalRawData

    /** conduct phenotyping */
    val phenotypeLabel = T2dmPhenotype.transform(medication, labResult, diagnostic)

    /** feature construction with all features */
    val featureTuples = sc.union(
      FeatureConstruction.constructDiagnosticFeatureTuple(diagnostic),
      FeatureConstruction.constructLabFeatureTuple(labResult),
      FeatureConstruction.constructMedicationFeatureTuple(medication)
    )

    val rawFeatures = FeatureConstruction.construct(sc, featureTuples)

    val (kMeansPurity, gaussianMixturePurity, nmfPurity) = testClustering(phenotypeLabel, rawFeatures)
    println(f"[All feature] purity of kMeans is: $kMeansPurity%.5f")
    println(f"[All feature] purity of GMM is: $gaussianMixturePurity%.5f")
    println(f"[All feature] purity of NMF is: $nmfPurity%.5f")

    /** feature construction with filtered features */
    val filteredFeatureTuples = sc.union(
      FeatureConstruction.constructDiagnosticFeatureTuple(diagnostic, candidateDiagnostic),
      FeatureConstruction.constructLabFeatureTuple(labResult, candidateLab),
      FeatureConstruction.constructMedicationFeatureTuple(medication, candidateMedication)
    )

    val filteredRawFeatures = FeatureConstruction.construct(sc, filteredFeatureTuples)

    val (kMeansPurity2, gaussianMixturePurity2, nmfPurity2) = testClustering(phenotypeLabel, filteredRawFeatures)
    println(f"[Filtered feature] purity of kMeans is: $kMeansPurity2%.5f")
    println(f"[Filtered feature] purity of GMM is: $gaussianMixturePurity2%.5f")
    println(f"[Filtered feature] purity of NMF is: $nmfPurity2%.5f")
    sc.stop 
  }

  def testClustering(phenotypeLabel: RDD[(String, Int)], rawFeatures:RDD[(String, Vector)]): (Double, Double, Double) = {
    import org.apache.spark.mllib.linalg.Matrix
    import org.apache.spark.mllib.linalg.distributed.RowMatrix

    /** scale features */
    val scaler = new StandardScaler(withMean = true, withStd = true).fit(rawFeatures.map(_._2))
    val features = rawFeatures.map({ case (patientID, featureVector) => (patientID, scaler.transform(Vectors.dense(featureVector.toArray)))})
    val rawFeatureVectors = features.map(_._2).cache()
    val rawFeatureIDs=features.map(_._1)

    /** reduce dimension */
    val mat: RowMatrix = new RowMatrix(rawFeatureVectors)
    val pc: Matrix = mat.computePrincipalComponents(10) // Principal components are stored in a local dense matrix.
    val featureVectors = mat.multiply(pc).rows

    val densePc = Matrices.dense(pc.numRows, pc.numCols, pc.toArray).asInstanceOf[DenseMatrix]
    /** transform a feature into its reduced dimension representation */
    def transform(feature: Vector): Vector={
    val scaled=scaler.transform(Vectors.dense(feature.toArray))
    Vectors.dense(Matrices.dense(1,scaled.size,scaled.toArray).multiply(densePc).toArray)
    }

    /** TODO: K Means Clustering using spark mllib
      *  Train a k means model using the variabe featureVectors as input
      *  Set maxIterations =20 and seed as 8803L
      *  Assign each feature vector to a cluster(predicted Class)
      *  Obtain an RDD[(Int, Int)] of the form (cluster number, RealClass)
      *  Find Purity using that RDD as an input to Metrics.purity
      *  Remove the placeholder below after your implementation
      **/

      //System.out.println("First call to purity")
      featureVectors.cache()
      val km = new KMeans().setSeed(8803).setK(15).setMaxIterations(20).run(featureVectors).predict(featureVectors)
      val kmresult=rawFeatureIDs.zip(km).join(phenotypeLabel).map(_._2)
      val kMeansPurity = Metrics.purity(kmresult)

    /** TODO: GMMM Clustering using spark mllib
      *  Train a Gaussian Mixture model using the variabe featureVectors as input
      *  Set maxIterations =20 and seed as 8803L
      *  Assign each feature vector to a cluster(predicted Class)
      *  Obtain an RDD[(Int, Int)] of the form (cluster number, RealClass)
      *  Find Purity using that RDD as an input to Metrics.purity
      *  Remove the placeholder below after your implementation
      **/

    val gmm = new GaussianMixture().setSeed(8803).setK(15).setMaxIterations(20).run(featureVectors).predict(featureVectors)
    val gmmResult=rawFeatureIDs.zip(gmm).join(phenotypeLabel).map(_._2)
    val gaussianMixturePurity = Metrics.purity(gmmResult)


    /** NMF */
    val (w, _) = NMF.run(new RowMatrix(rawFeatureVectors), 5, 100)
    val assignments = w.rows.map(_.toArray.zipWithIndex.maxBy(_._1)._2)
    val labels = features.join(phenotypeLabel).map({ case (patientID, (feature, realClass)) => realClass})
    val nmfRes = assignments.zipWithIndex().map(_.swap).join(labels.zipWithIndex().map(_.swap)).map(_._2)
    val nmfPurity = Metrics.purity(nmfRes)

    (kMeansPurity, gaussianMixturePurity, nmfPurity)
  }

  /**
   * load the sets of string for filtering of medication
   * lab result and diagnostics
    *
    * @return
   */
  def convert(labValue: String): Double = {
    if (labValue != null && labValue.nonEmpty) {
      val labValueResult = labValue replaceAll("[,]", "")
      labValueResult.toDouble
    }
    else {
      -1.0
    }
  }

  def loadLocalRawData: (Set[String], Set[String], Set[String]) = {
    val candidateMedication = Source.fromFile("data/med_filter.txt").getLines().map(_.toLowerCase).toSet[String]
    val candidateLab = Source.fromFile("data/lab_filter.txt").getLines().map(_.toLowerCase).toSet[String]
    val candidateDiagnostic = Source.fromFile("data/icd9_filter.txt").getLines().map(_.toLowerCase).toSet[String]
    (candidateMedication, candidateLab, candidateDiagnostic)
  }


  def loadRddRawData(sqlContext: SQLContext): (RDD[Medication], RDD[LabResult], RDD[Diagnostic]) = {
    /** You may need to use this date format. */
    val dateFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssX")

    /** load data using Spark SQL into three RDDs and return them
      * Hint: You can utilize edu.gatech.cse8803.ioutils.CSVUtils and SQLContext.
      *
      * Notes:Refer to model/models.scala for the shape of Medication, LabResult, Diagnostic data type.
      *       Be careful when you deal with String and numbers in String type.
      *       Ignore lab results with missing (empty or NaN) values when these are read in.
      *       For dates, use Date_Resulted for labResults and Order_Date for medication.
      * */

    /** TODO: implement your own code here and remove existing placeholder code below */

    val medRows = CSVUtils.loadCSVAsTable(sqlContext, "data/medication_orders_INPUT.csv", "medicine")
    val medRddRows = sqlContext.sql("Select Member_ID,Order_Date,Drug_Name From medicine")
    val medication: RDD[Medication] = medRddRows.map(p => Medication(p(0).toString, dateFormat.parse(p(1).toString), p(2).toString.toLowerCase))

    val labRows = CSVUtils.loadCSVAsTable(sqlContext, "data/lab_results_INPUT.csv", "labs")
    val labRddRows = sqlContext.sql("Select Member_ID, Date_Resulted, Result_Name, Numeric_Result From labs")
    val labrdd = labRddRows.rdd
    val labResult: RDD[LabResult] = labrdd.filter(s=>(s.getString(3)!=null) && (s.getString(3).nonEmpty)).map(p=>LabResult(p(0).toString, dateFormat.parse(p(1).toString), p(2).toString.toLowerCase, convert(p(3).toString)))
    val df = labResult.map(LabResult=>LabResult.patientID).distinct()

    val diagRows = CSVUtils.loadCSVAsTable(sqlContext, "data/encounter_INPUT.csv", "diag")
    val diagRddRows = sqlContext.sql("Select Member_ID, Encounter_ID, Encounter_DateTime From diag")
    val icdRows = CSVUtils.loadCSVAsTable(sqlContext, "data/encounter_dx_INPUT.csv", "icd")
    val icdRddRows = sqlContext.sql("Select Encounter_ID, code From icd")
    val diagnosticRows = sqlContext.sql("Select d.Member_ID, d.Encounter_DateTime, i.code From diag d JOIN icd i ON d.Encounter_ID = i.Encounter_ID")
    val diagnostic: RDD[Diagnostic] = diagnosticRows.map(p => Diagnostic(p(0).toString, dateFormat.parse(p(1).toString), p(2).toString))

    (medication, labResult, diagnostic)
  }

  def createContext(appName: String, masterUrl: String): SparkContext = {
    val conf = new SparkConf().setAppName(appName).setMaster(masterUrl)
    new SparkContext(conf)
  }

  def createContext(appName: String): SparkContext = createContext(appName, "local")

  def createContext: SparkContext = createContext("CSE 8803 Homework Two Application", "local")
}