package sampleclean.api

import org.apache.spark.sql.SchemaRDD
import org.apache.spark.sql.hive.HiveContext
import org.apache.spark.SparkContext
import org.apache.spark.sql.Row

import sampleclean.util.TypeUtils._
import sampleclean.parse.SampleCleanParser;
import sampleclean.parse.SampleCleanParser._;

/* This class provides the approximate query processing 
* for SampleClean. Currently, it supports SUM, COUNT, AVG
* and returns confidence intervals in the form of CLT variance
* estimates.
*/
@serializable
class SampleCleanAQP() {

	  /**This function executes the per-partition query processing of the agg function
	   */
	  private def aqpPartitionAgg(partitionData:Iterator[Double]): Iterator[(Double,Double,Double)] =
	  {
	  		var result = 0.0
	  		var variance = 0.0
	  		var n = 0.0

	  		for(tuple <- partitionData)
	  		{
	  			n = n + 1
	  			result = result*((n-1)/n) + tuple/n
	  			val dev = Math.pow((tuple - result),2)
	  			variance = variance*((n-1)/n) + dev/n
	  		}

	  		return List((result,variance, n)).iterator
	  }

	  //Helper function that "transforms" our queries into mean queries
	  private def aqpPartitionMap(row:Row, transform: Double => Double): Double = 
	  {
	  		return transform(rowToNumber(row,0))/rowToNumber(row,1)
	  }

	  //approximate count, sum, avg
	  //The basic idea is we aggregate an average
	  //on each split then average them together and
	  //rescale 

	  private def approxCount(rdd:SchemaRDD, sampleRatio:Double):(Double, Double)=
	  {

	  	  val partitionResults = rdd.map(row => aqpPartitionMap(row,x => x))
	  	  							.mapPartitions(aqpPartitionAgg, true).collect()
	  	  var count:Double = 0.0
	  	  var variance:Double = 0.0
	  	  var emptyPartitions = 0
	  	  for(p <- partitionResults)
	  	  {
	  	  	count = count + p._1.asInstanceOf[Double]
	  	  	variance = variance + p._2.asInstanceOf[Double]
	  	  	
	  	  	if (p._3.asInstanceOf[Double] == 0.0)
	  	  		emptyPartitions = emptyPartitions + 1
	  	  }
	  	  val splitSize = partitionResults.length - emptyPartitions
	  	  return (rdd.count()*count/(splitSize*sampleRatio),
	  	  	      (rdd.count()/sampleRatio)*Math.sqrt(variance/splitSize)/
	  	  	       Math.sqrt(rdd.count()))
	  }


	  private def approxSum(rdd:SchemaRDD, sampleRatio:Double):(Double, Double)=
	  {

	  	  val partitionResults = rdd.map(row => aqpPartitionMap(row,x => x))
	  	  							.mapPartitions(aqpPartitionAgg, true).collect()
	  	  var sum:Double = 0.0
	  	  var variance:Double = 0.0
	  	  var emptyPartitions = 0
	  	  for(p <- partitionResults)
	  	  {
	  	  	sum = sum + p._1.asInstanceOf[Double]
	  	  	variance = variance + p._2.asInstanceOf[Double]

	  	  	if (p._3.asInstanceOf[Double] == 0.0)
	  	  		emptyPartitions = emptyPartitions + 1
	  	  }
	  	  val splitSize = partitionResults.length - emptyPartitions
	  	  return (rdd.count()*sum/(splitSize*sampleRatio),
	  	  	       (rdd.count()/sampleRatio)*Math.sqrt(variance/splitSize)/
	  	  	       Math.sqrt(rdd.count()))
	  }

	  private def duplicationRate(rdd:SchemaRDD):Double=
	  {
	  	  return rdd.count()/rdd.map( x => 1.0/x(1).asInstanceOf[Int]).reduce(_ + _)
	  }

	  private def approxAvg(rdd:SchemaRDD, sampleRatio:Double):(Double, Double)=
	  {
	  	  val partitionResults = rdd.map(row => aqpPartitionMap(row,x => x))
	  	  							.mapPartitions(aqpPartitionAgg, true).collect()
	  	  var sum:Double = 0.0
	  	  var variance:Double = 0.0
	  	  var emptyPartitions = 0
	  	  for(p <- partitionResults)
	  	  {
	  	  	sum = sum + p._1.asInstanceOf[Double]
	  	  	variance = variance + p._2.asInstanceOf[Double]
	  	  	if (p._3.asInstanceOf[Double] == 0.0)
	  	  		emptyPartitions = emptyPartitions + 1
	  	  }
	  	  val splitSize = partitionResults.length - emptyPartitions
	  	  return (duplicationRate(rdd)*sum/(splitSize)
	  	  	     ,duplicationRate(rdd)*Math.sqrt(variance/splitSize)/
	  	  	     Math.sqrt(rdd.count()))
	  }

	  /*This query executes rawSC given an attribute to aggregate, expr {SUM, COUNT, AVG}, a predicate, and the sampling ratio.
	  * It returns a tuple of the estimate, and the variance of the estimate (EST, VAR_EST)
	  */
	  def rawSCQuery(scc:SampleCleanContext, sampleName: String, 
	  				  attr: String, expr: String, 
	  				  pred:String, 
	  				  sampleRatio: Double): (Double, Double)=
	  {
	  	  val hc:HiveContext = scc.getHiveContext()
	  	  val baseTable = sampleName + "_clean"

	  	  var query = ""
	  	  if (expr.toLowerCase() == "avg"){
	  	  	 query = "SELECT " + attr + ",dup FROM " + baseTable + " where " + pred 
	  	  	 return approxAvg(hc.hql(query),sampleRatio)
	  	  }
	  	  else if (expr.toLowerCase() == "sum"){
	  	  	 query = "SELECT " + attr + "*if((" + pred + "),1.0,0.0), dup FROM " + baseTable
	  	  	 return approxSum(hc.hql(query),sampleRatio)
	  	  	}
	  	  else
	  	  {
	  	  	query = "SELECT if((" + pred + "),1.0,0.0), dup FROM " + baseTable
	  	  	 return approxCount(hc.hql(query),sampleRatio)
	  	  }

	  }

	  /*This query executes rawSC given an attribute to aggregate, expr {SUM, COUNT, AVG}, a predicate, and the sampling ratio.
	  * It returns a tuple of the estimate, and the variance of the estimate (EST, VAR_EST)
	  * (To clean up)
	  */
	 def normalizedSCQuery(scc:SampleCleanContext, sampleName: String, 
	  				  attr: String, expr: String, 
	  				  pred:String, 
	  				  sampleRatio: Double): (Double, Double)=
	  {
	  	  val hc:HiveContext = scc.getHiveContext()
	  	  val baseTableClean = sampleName + "_clean"
	  	  val baseTableDirty = sampleName + "_dirty"

	  	  val newPred = makeExpressionExplicit(pred,baseTableClean)
	  	  val oldPred = makeExpressionExplicit(pred,baseTableDirty)
	  	  val typeSafeCleanAttr = makeExpressionExplicit(typeSafeHQL(attr),baseTableClean)
	  	  val typeSafeDirtyAttr = makeExpressionExplicit(typeSafeHQL(attr),baseTableDirty)
	  	  val typeSafeDup = makeExpressionExplicit(typeSafeHQL("dup",1),baseTableClean)
	  	  val selectionStringAVG = typeSafeDirtyAttr + " - " + typeSafeCleanAttr +"/" + typeSafeDup + ",1"
	  	  val selectionStringSUM = "("+typeSafeDirtyAttr+"*if((" + oldPred + "),1.0,0.0))" + " - (" + typeSafeCleanAttr+"*if((" + newPred + "),1.0,0.0))/" +typeSafeDup + ",1"
	  	  val selectionStringCOUNT = "if((" + oldPred + "),1.0,0.0) - if((" + newPred + "),1.0,0.0)/"+ typeSafeDup +",1"

	  	  var query = ""
	  	  if (expr.toLowerCase() == "avg"){
	  	  	 query = 	 " SELECT "+ 
   			            selectionStringAVG+" FROM "+
   			            baseTableDirty+" LEFT OUTER JOIN " + 
   			            baseTableClean+" ON ("+
   			            baseTableClean+".hash = "+baseTableDirty+".hash)"
	  	  	 return approxAvg(hc.hql(query),sampleRatio)
	  	  }
	  	  else if (expr.toLowerCase() == "sum"){
	  	  	 query =  " SELECT "+
   			            selectionStringSUM+" FROM "+
   			            baseTableDirty+" LEFT OUTER JOIN " + 
   			            baseTableClean+" ON ("+
   			            baseTableClean+".hash = "+baseTableDirty+".hash)"
			 println(query)
	  	  	 return approxSum(hc.hql(query),sampleRatio)
	  	  	}
	  	  else
	  	  {
	  	  	query = " SELECT "+ 
   			            selectionStringCOUNT+" FROM "+
   			            baseTableDirty+" LEFT OUTER JOIN " + 
   			            baseTableClean+" ON ("+
   			            baseTableClean+".hash = "+baseTableDirty+".hash)"
			 
	  	  	 return approxSum(hc.hql(query),sampleRatio)
	  	  }

	  }

	  /*This query executes rawSC given an attribute to aggregate, expr {SUM, COUNT, AVG}, a predicate, and the sampling ratio.
	  * It returns a tuple of the estimate, and the variance of the estimate (EST, VAR_EST)
	  */
	  def rawSCQuery(scc:SampleCleanContext, rdd:SchemaRDD, 
	  				  attr: String, expr: String, 
	  				  pred:String, 
	  				  sampleRatio: Double): (Double, Double)=
	  {
	  	  val hc:HiveContext = scc.getHiveContext()
	  	  hc.registerRDDAsTable(rdd,"tmp")
	  	  val baseTable = "tmp"

	  	  var query = ""
	  	  if (expr.toLowerCase() == "avg"){
	  	  	 query = "SELECT " + attr + ",dup FROM " + baseTable + " where " + pred 
	  	  	 return approxAvg(hc.hql(query),sampleRatio)
	  	  }
	  	  else if (expr.toLowerCase() == "sum"){
	  	  	 query = "SELECT " + attr + "*if((" + pred + "),1.0,0.0), dup FROM " + baseTable
	  	  	 return approxSum(hc.hql(query),sampleRatio)
	  	  	}
	  	  else
	  	  {
	  	  	query = "SELECT if((" + pred + "),1.0,0.0), dup FROM " + baseTable
	  	  	 return approxCount(hc.hql(query),sampleRatio)
	  	  }

	  }

	 
}
