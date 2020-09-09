package org.ekstep.analytics.api.service

import java.util.Date

import com.typesafe.config.ConfigFactory
import org.apache.commons.lang3.StringUtils
import org.ekstep.analytics.api._
import org.ekstep.analytics.api.util._
import org.ekstep.analytics.framework.FrameworkContext
import org.ekstep.analytics.framework.conf.AppConf
import org.joda.time.{DateTime, LocalDate}
import org.scalamock.scalatest.MockFactory
import org.scalatest.{BeforeAndAfterAll, FlatSpec, Matchers}
import org.sunbird.cloud.storage.BaseStorageService

import scala.collection.immutable.List
import org.mockito.Mockito._
import org.mockito.ArgumentMatchers
import akka.actor.ActorSystem
import akka.testkit.TestActorRef
import akka.actor.ActorRef
import org.ekstep.analytics.api.service.{ChannelData, DataRequest, DataRequestList, GetDataRequest}

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.concurrent.ExecutionContextExecutor
import akka.util.Timeout

class TestJobAPIService extends BaseSpec  {
  
  implicit val mockFc = mock[FrameworkContext];
  private val mockStorageService = mock[BaseStorageService]
  private implicit val system: ActorSystem = ActorSystem("test-actor-system", config)
  private val postgresUtil = new PostgresDBUtil
  val jobApiServiceActorRef = TestActorRef(new JobAPIService(postgresUtil))
  implicit val executionContext: ExecutionContextExecutor =  scala.concurrent.ExecutionContext.global
  implicit val timeout: Timeout = 20.seconds


  override def beforeAll(): Unit = {
    super.beforeAll()
    EmbeddedPostgresql.start()
    EmbeddedPostgresql.createTables()
  }

  override def afterAll(): Unit = {
    super.afterAll()
    EmbeddedPostgresql.close()
  }


  "JobAPIService" should "return response for data request" in {
      val request = """{"id":"ekstep.analytics.data.out","ver":"1.0","ts":"2016-12-07T12:40:40+05:30","params":{"msgid":"4f04da60-1e24-4d31-aa7b-1daf91c46341"},"request":{"tag":"test-client","requestedBy":"test-1","jobId":"assessment-score-report","jobConfig":{"batchFilters":["TPD","NCFCOPY"],"contentFilters":{"request":{"filters":{"identifier":["do_11305960936384921612216","do_1130934466492252161819"],"prevState":"Draft"},"sort_by":{"createdOn":"desc"},"limit":10000,"fields":["framework","identifier","name","channel","prevState"]}},"reportPath":"course-progress-v2/"},"output_format":"csv"}}"""
      val response = jobApiServiceActorRef.underlyingActor.dataRequest(request, "in.ekstep")
      response.responseCode should be("OK")
  }

  "JobAPIService" should "return response for data request which is completed when submitted request for already completed job" in {

    EmbeddedPostgresql.execute(
      s"""insert into job_request ("tag", "request_id", "job_id", "status", "request_data", "requested_by",
        "requested_channel", "dt_job_submitted", "dt_job_completed", "download_urls", "dt_file_created", "execution_time") values ('client-1', '462CDD1241226D5CA2E777DA522691EF', 'assessment-score-report',
        'COMPLETED',  '{"batchFilters":["TPD","NCFCOPY"],"contentFilters":{"request":{"filters":{"identifier":["do_11305960936384921612216","do_1130934466492252161819"],"prevState":"Draft"},"sort_by":{"createdOn":"desc"},"limit":10000,"fields":["framework","identifier","name","channel","prevState"]}},"reportPath":"course-progress-v2/"}',
        'test-1', 'in.ekstep' , '2020-09-07T13:54:39.019+05:30', '2020-09-08T13:54:39.019+05:30', '{"file1.csv", "file2.csv"}', '2020-09-08T13:50:39.019+05:30', '10');""")

    reset(mockStorageService)
    when(mockFc.getStorageService(ArgumentMatchers.any())).thenReturn(mockStorageService);
    when(mockStorageService.getSignedURL(ArgumentMatchers.any(),ArgumentMatchers.any(),ArgumentMatchers.any(),ArgumentMatchers.any())).thenReturn("https://sunbird.org/test/signed/file1.csv");
    doNothing().when(mockStorageService).closeContext()

    val res = jobApiServiceActorRef.underlyingActor.getDataRequest("client-1", "462CDD1241226D5CA2E777DA522691EF")
    res.responseCode should be("OK")
    val responseData = JSONUtils.deserialize[JobResponse](JSONUtils.serialize(res.result.get))
    responseData.download_urls.get.size should be(2)
    responseData.status should be("COMPLETED")

    val request = """{"id":"ekstep.analytics.data.out","ver":"1.0","ts":"2016-12-07T12:40:40+05:30","params":{"msgid":"4f04da60-1e24-4d31-aa7b-1daf91c46341"},"request":{"tag":"client-1","requestedBy":"test-1","jobId":"assessment-score-report","jobConfig":{"batchFilters":["TPD","NCFCOPY"],"contentFilters":{"request":{"filters":{"identifier":["do_11305960936384921612216","do_1130934466492252161819"],"prevState":"Draft"},"sort_by":{"createdOn":"desc"},"limit":10000,"fields":["framework","identifier","name","channel","prevState"]}},"reportPath":"course-progress-v2/"},"output_format":"csv"}}"""
    val res1 = jobApiServiceActorRef.underlyingActor.dataRequest(request, "in.ekstep")
    res1.responseCode should be("OK")
    val responseData1 = JSONUtils.deserialize[JobResponse](JSONUtils.serialize(res1.result.get))
    responseData1.status should be("SUBMITTED")
  }


  "JobAPIService" should "return failed response for data request with empty tag in request" in {
    val request = """{"id":"ekstep.analytics.data.out","ver":"1.0","ts":"2016-12-07T12:40:40+05:30","params":{"msgid":"4f04da60-1e24-4d31-aa7b-1daf91c46341"},"request":{"jobId":"assessment-score-report","jobConfig":{"batchFilters":["TPD","NCFCOPY"],"contentFilters":{"request":{"filters":{"identifier":["do_11305960936384921612216","do_1130934466492252161819"],"prevState":"Draft"},"sort_by":{"createdOn":"desc"},"limit":10000,"fields":["framework","identifier","name","channel","prevState"]}},"reportPath":"course-progress-v2/"},"output_format":"csv"}}"""
    val response = jobApiServiceActorRef.underlyingActor.dataRequest(request, "in.ekstep")
    response.params.status should be("failed")
    response.params.errmsg should be ("tag is empty")
  }

  "JobAPIService" should "return failed response for data request with empty jobId in request" in {
    val request = """{"id":"ekstep.analytics.data.out","ver":"1.0","ts":"2016-12-07T12:40:40+05:30","params":{"msgid":"4f04da60-1e24-4d31-aa7b-1daf91c46341"},"request":{"tag":"client-1","jobConfig":{"batchFilters":["TPD","NCFCOPY"],"contentFilters":{"request":{"filters":{"identifier":["do_11305960936384921612216","do_1130934466492252161819"],"prevState":"Draft"},"sort_by":{"createdOn":"desc"},"limit":10000,"fields":["framework","identifier","name","channel","prevState"]}},"reportPath":"course-progress-v2/"},"output_format":"csv"}}"""
    val response = jobApiServiceActorRef.underlyingActor.dataRequest(request, "in.ekstep")
    response.params.status should be("failed")
    response.params.errmsg should be ("jobId is empty")
  }
  
  it should "validate the request body" in {
    var response = jobApiServiceActorRef.underlyingActor.dataRequest("""{"id":"ekstep.analytics.data.out","ver":"1.0","ts":"2016-12-07T12:40:40+05:30","params":{"msgid":"4f04da60-1e24-4d31-aa7b-1daf91c46341"},"request":{"tag":"client-1","jobId":"assessment-score-report","jobConfig":{"batchFilters":["TPD","NCFCOPY"],"contentFilters":{"request":{"filters":{"identifier":["do_11305960936384921612216","do_1130934466492252161819"],"prevState":"Draft"},"sort_by":{"createdOn":"desc"},"limit":10000,"fields":["framework","identifier","name","channel","prevState"]}},"reportPath":"course-progress-v2/"},"output_format":"proto"}}""", "in.ekstep")
    response.params.errmsg should be ("invalid type. It should be one of [csv, json].")
    
    response = jobApiServiceActorRef.underlyingActor.dataRequest("""{"id":"ekstep.analytics.data.out","ver":"1.0","ts":"2016-12-07T12:40:40+05:30","params":{"msgid":"4f04da60-1e24-4d31-aa7b-1daf91c46341"},"request":{"jobId":"assessment-score-report","jobConfig":{"batchFilters":["TPD","NCFCOPY"],"contentFilters":{"request":{"filters":{"identifier":["do_11305960936384921612216","do_1130934466492252161819"],"prevState":"Draft"},"sort_by":{"createdOn":"desc"},"limit":10000,"fields":["framework","identifier","name","channel","prevState"]}},"reportPath":"course-progress-v2/"},"output_format":"csv"}}""", "in.ekstep")
    response.params.errmsg should be ("tag is empty")

    response = jobApiServiceActorRef.underlyingActor.dataRequest("""{"id":"ekstep.analytics.data.out","ver":"1.0","ts":"2016-12-07T12:40:40+05:30","params":{"msgid":"4f04da60-1e24-4d31-aa7b-1daf91c46341"},"request":{"tag":"client-1","jobConfig":{"batchFilters":["TPD","NCFCOPY"],"contentFilters":{"request":{"filters":{"identifier":["do_11305960936384921612216","do_1130934466492252161819"],"prevState":"Draft"},"sort_by":{"createdOn":"desc"},"limit":10000,"fields":["framework","identifier","name","channel","prevState"]}},"reportPath":"course-progress-v2/"},"output_format":"csv"}}""", "in.ekstep")
    response.params.errmsg should be ("jobId is empty")

  }

  it should "return response for get data request" in {
    val response = jobApiServiceActorRef.underlyingActor.getDataRequest("dev-portal", "14621312DB7F8ED99BA1B16D8B430FAC")
    response.responseCode should be("OK")
  }

  it should "return the list of jobs in descending order" in {

    EmbeddedPostgresql.execute(
      s"""insert into job_request ("tag", "request_id", "job_id", "status", "request_data", "requested_by",
        "requested_channel", "dt_job_submitted", "dt_job_completed", "download_urls", "dt_file_created", "execution_time") values ('client-2', '462CDD1241226D5CA2E777DA522691EF', 'assessment-score-report',
        'COMPLETED',  '{"batchFilters":["TPD","NCFCOPY"],"contentFilters":{"request":{"filters":{"identifier":["do_11305960936384921612216","do_1130934466492252161819"],"prevState":"Draft"},"sort_by":{"createdOn":"desc"},"limit":10000,"fields":["framework","identifier","name","channel","prevState"]}},"reportPath":"course-progress-v2/"}',
        'test-1', 'in.ekstep' , '2020-09-07T13:54:39.019+05:30', '2020-09-08T13:54:39.019+05:30', '{"file1.csv", "file2.csv"}', '2020-09-08T13:50:39.019+05:30', '10');""")

    EmbeddedPostgresql.execute(
      s"""insert into job_request ("tag", "request_id", "job_id", "status", "request_data", "requested_by",
        "requested_channel", "dt_job_submitted", "dt_job_completed", "download_urls", "dt_file_created", "execution_time") values ('client-2', '562CDD1241226D5CA2E777DA522691EF', 'assessment-score-report',
        'COMPLETED',  '{"batchFilters":["TPD","NCFCOPY"],"contentFilters":{"request":{"filters":{"identifier":["do_1130596093638492161","do_1130934466492252169"],"prevState":"Draft"},"sort_by":{"createdOn":"desc"},"limit":10000,"fields":["framework","identifier","name","channel","prevState"]}},"reportPath":"course-progress-v2/"}',
        'test-1', 'in.ekstep' , '2020-09-07T13:55:39.019+05:30', '2020-09-08T14:54:39.019+05:30', '{"file1.csv", "file2.csv"}', '2020-09-08T13:53:39.019+05:30', '5');""")

    reset(mockStorageService)
    when(mockFc.getStorageService(ArgumentMatchers.any())).thenReturn(mockStorageService);
    when(mockStorageService.getSignedURL(ArgumentMatchers.any(),ArgumentMatchers.any(),ArgumentMatchers.any(),ArgumentMatchers.any())).thenReturn("https://sunbird.org/test/signed/file1.csv");
    doNothing().when(mockStorageService).closeContext()

    val res = jobApiServiceActorRef.underlyingActor.getDataRequestList("client-2", 10)
    val resultMap = res.result.get
    val jobRes = JSONUtils.deserialize[List[JobResponse]](JSONUtils.serialize(resultMap.get("jobs").get))
    jobRes.length should be(2)

    // fetch data with limit less than the number of record available
    val res2 = jobApiServiceActorRef.underlyingActor.getDataRequestList("client-2", 1)
    val resultMap2 = res2.result.get
    val jobRes2 = JSONUtils.deserialize[List[JobResponse]](JSONUtils.serialize(resultMap2.get("jobs").get))
    jobRes2.length should be(1)

    // trying to fetch the record with a key for which data is not available
    val res1 = jobApiServiceActorRef.underlyingActor.getDataRequestList("testKey", 10)
    val resultMap1 = res1.result.get.asInstanceOf[Map[String, AnyRef]]
    resultMap1.get("count").get.asInstanceOf[Int] should be(0)
  }

  it should "re-submit job if it is already completed" in {

    EmbeddedPostgresql.execute(
      s"""insert into job_request ("tag", "request_id", "job_id", "status", "request_data", "requested_by",
        "requested_channel", "dt_job_submitted", "dt_job_completed", "download_urls", "dt_file_created", "execution_time") values ('client-3', '17CB7C4AC4202ABC0605407058EE0504', 'assessment-score-report',
        'COMPLETED',  '{"batchFilters":["TPD","NCFCOPY"],"contentFilters":{"request":{"filters":{"identifier":["do_11305960936384921612216","do_1130934466492252161819"],"prevState":"Draft"},"sort_by":{"createdOn":"desc"},"limit":10000,"fields":["framework","identifier","name","channel","prevState"]}},"reportPath":"course-progress-v2/"}',
        'test-1', 'in.ekstep' , '2020-09-07T13:54:39.019+05:30', '2020-09-08T13:54:39.019+05:30', '{"file1.csv", "file2.csv"}', '2020-09-08T13:50:39.019+05:30', '10');""")

    reset(mockStorageService)
    when(mockFc.getStorageService(ArgumentMatchers.any())).thenReturn(mockStorageService);
    when(mockStorageService.getSignedURL(ArgumentMatchers.any(),ArgumentMatchers.any(),ArgumentMatchers.any(),ArgumentMatchers.any())).thenReturn("https://sunbird.org/test/signed/file1.csv");
    doNothing().when(mockStorageService).closeContext()

    val res = jobApiServiceActorRef.underlyingActor.getDataRequest("client-3", "17CB7C4AC4202ABC0605407058EE0504")
    res.responseCode should be("OK")
    val responseData = JSONUtils.deserialize[JobResponse](JSONUtils.serialize(res.result.get))
    responseData.download_urls.get.size should be(2)
    responseData.status should be("COMPLETED")

    val request = """{"id":"ekstep.analytics.data.out","ver":"1.0","ts":"2016-12-07T12:40:40+05:30","params":{"msgid":"4f04da60-1e24-4d31-aa7b-1daf91c46341"},"request":{"tag":"client-3","requestedBy":"test-1","jobId":"assessment-score-report","jobConfig":{"batchFilters":["TPD","NCFCOPY"],"contentFilters":{"request":{"filters":{"identifier":["do_11305960936384921612216","do_1130934466492252161819"],"prevState":"Draft"},"sort_by":{"createdOn":"desc"},"limit":10000,"fields":["framework","identifier","name","channel","prevState"]}},"reportPath":"course-progress-v2/"},"output_format":"csv"}}"""
    val res1 = jobApiServiceActorRef.underlyingActor.dataRequest(request, "in.ekstep")
    res1.responseCode should be("OK")
    val responseData1 = JSONUtils.deserialize[JobResponse](JSONUtils.serialize(res1.result.get))
    responseData1.status should be("SUBMITTED")
  }

  "JobAPIService" should "return different request id for same tag having different requested channel" in {
    val request1 = """{"id":"ekstep.analytics.data.out","ver":"1.0","ts":"2016-12-07T12:40:40+05:30","params":{"msgid":"4f04da60-1e24-4d31-aa7b-1daf91c46341"},"request":{"tag":"client-2","requestedBy":"test-1","jobId":"assessment-score-report","jobConfig":{"batchFilters":["TPD","NCFCOPY"],"contentFilters":{"request":{"filters":{"identifier":["do_11305960936384921612216","do_1130934466492252161819"],"prevState":"Draft"},"sort_by":{"createdOn":"desc"},"limit":10000,"fields":["framework","identifier","name","channel","prevState"]}},"reportPath":"course-progress-v2/"},"output_format":"csv"}}"""
    val response1 = jobApiServiceActorRef.underlyingActor.dataRequest(request1, "test-channel-1")
    val request2 = """{"id":"ekstep.analytics.data.out","ver":"1.0","ts":"2016-12-07T12:40:40+05:30","params":{"msgid":"4f04da60-1e24-4d31-aa7b-1daf91c46341"},"request":{"tag":"client-2","requestedBy":"test-1","jobId":"assessment-score-report","jobConfig":{"batchFilters":["TPD","NCFCOPY"],"contentFilters":{"request":{"filters":{"identifier":["do_11305960936384921612216","do_1130934466492252161819"],"prevState":"Draft"},"sort_by":{"createdOn":"desc"},"limit":10000,"fields":["framework","identifier","name","channel","prevState"]}},"reportPath":"course-progress-v2/"},"output_format":"csv"}}"""
    val response2 = jobApiServiceActorRef.underlyingActor.dataRequest(request2, "test-channel-2")
    response2.result.head.get("request_id").get should not be (response1.result.head.get("request_id").get)

  }

  //  // Channel Exhaust Test Cases
  //  // -ve Test cases
  it should "return response for default datasetId if we set `datasetID` other than valid" in {

      reset(mockStorageService)
      when(mockFc.getStorageService(ArgumentMatchers.any())).thenReturn(mockStorageService);
      when(mockStorageService.upload(ArgumentMatchers.any(),ArgumentMatchers.any(),ArgumentMatchers.any(),ArgumentMatchers.any(),ArgumentMatchers.any(),ArgumentMatchers.any(),ArgumentMatchers.any())).thenReturn("");
      when(mockStorageService.getSignedURL(ArgumentMatchers.any(),ArgumentMatchers.any(),ArgumentMatchers.any(),ArgumentMatchers.any())).thenReturn("");
      when(mockStorageService.searchObjectkeys(ArgumentMatchers.any(),ArgumentMatchers.any(),ArgumentMatchers.any(),ArgumentMatchers.any(),ArgumentMatchers.any(),ArgumentMatchers.any())).thenReturn(List());
      doNothing().when(mockStorageService).closeContext()

      val datasetId = "test"
      val resObj = jobApiServiceActorRef.underlyingActor.getChannelData("in.ekstep", datasetId, "2018-05-14", "2018-05-15")
      resObj.responseCode should be("OK")
      val res = resObj.result.getOrElse(Map())
      val urls = res.get("files").get.asInstanceOf[List[String]];
      urls.size should be (0)
  }

  it should "return a CLIENT_ERROR in the response if 'fromDate' is empty and taking previous day by default" in {
    val fromDate = ""
    val resObj = jobApiServiceActorRef.underlyingActor.getChannelData("in.ekstep", "raw", fromDate, "2018-05-15")
    resObj.responseCode should be("CLIENT_ERROR")
    resObj.params.errmsg should be("Date range should not be -ve. Please check your 'from' & 'to'")
  }

  it should "return a CLIENT_ERROR in the response if 'endDate' is empty older than fromDate" in {
    val toDate = "2018-05-10"
    val resObj = jobApiServiceActorRef.underlyingActor.getChannelData("in.ekstep", "raw", "2018-05-15", toDate)
    resObj.responseCode should be("CLIENT_ERROR")
    resObj.params.errmsg should be("Date range should not be -ve. Please check your 'from' & 'to'")
  }

  it should "return a CLIENT_ERROR in the response if 'endDate' is a future date" in {
    val toDate = new LocalDate().plusDays(1).toString()
    val resObj = jobApiServiceActorRef.underlyingActor.getChannelData("in.ekstep", "raw", "2018-05-15", toDate)
    resObj.responseCode should be("CLIENT_ERROR")
    resObj.params.errmsg should be("'to' should be LESSER OR EQUAL TO today's date..")
  }
  //
  it should "return a CLIENT_ERROR in the response if date_range > 10" in {
    val toDate = new LocalDate().toString()
    val fromDate = new LocalDate().minusDays(11).toString()

    val resObj = jobApiServiceActorRef.underlyingActor.getChannelData("in.ekstep", "raw", fromDate, toDate)
    resObj.responseCode should be("CLIENT_ERROR")
    resObj.params.errmsg should be("Date range should be < 10 days")
  }
  //
  //  // +ve test cases
  //
  ignore should "return a successfull response if 'to' is empty" in {
    val toDate = ""
    val resObj = jobApiServiceActorRef.underlyingActor.getChannelData("in.ekstep", "raw", "2018-05-20", toDate)
    resObj.responseCode should be("OK")
  }

  ignore should "return a successfull response if datasetID is valid - S3" in {
    val datasetId = "raw"
    val resObj = jobApiServiceActorRef.underlyingActor.getChannelData("in.ekstep", datasetId, "2018-05-20", "2018-05-21")
    resObj.responseCode should be("OK")
  }

  it should "get the channel data for raw data" in {
    
    reset(mockStorageService)
    when(mockFc.getStorageService(ArgumentMatchers.any())).thenReturn(mockStorageService);
    when(mockStorageService.upload(ArgumentMatchers.any(),ArgumentMatchers.any(),ArgumentMatchers.any(),ArgumentMatchers.any(),ArgumentMatchers.any(),ArgumentMatchers.any(),ArgumentMatchers.any())).thenReturn("");
    when(mockStorageService.getSignedURL(ArgumentMatchers.any(),ArgumentMatchers.any(),ArgumentMatchers.any(),ArgumentMatchers.any())).thenReturn("");
    when(mockStorageService.searchObjectkeys(ArgumentMatchers.any(),ArgumentMatchers.any(),ArgumentMatchers.any(),ArgumentMatchers.any(),ArgumentMatchers.any(),ArgumentMatchers.any())).thenReturn(List());
    doNothing().when(mockStorageService).closeContext()
    
    val resObj = jobApiServiceActorRef.underlyingActor.getChannelData("in.ekstep", "raw", "2018-05-20", "2018-05-20")
    resObj.responseCode should be("OK")
    val res = resObj.result.getOrElse(Map())
    val urls = res.get("files").get.asInstanceOf[List[String]];
    urls.size should be (0)
    val periodWiseFiles = res.get("periodWiseFiles").get.asInstanceOf[Map[String,List[String]]];
    periodWiseFiles.size should be (0)
  }
  
  it should "get the channel data for summary data" in {
    
    reset(mockStorageService)
    when(mockFc.getStorageService(ArgumentMatchers.any())).thenReturn(mockStorageService);
    when(mockStorageService.upload(ArgumentMatchers.any(),ArgumentMatchers.any(),ArgumentMatchers.any(),ArgumentMatchers.any(),ArgumentMatchers.any(),ArgumentMatchers.any(),ArgumentMatchers.any())).thenReturn("");
    when(mockStorageService.getSignedURL(ArgumentMatchers.any(),ArgumentMatchers.any(),ArgumentMatchers.any(),ArgumentMatchers.any())).thenReturn("https://sunbird.org/test/signed/2018-05-20.json");
    when(mockStorageService.searchObjectkeys(ArgumentMatchers.any(),ArgumentMatchers.any(),ArgumentMatchers.any(),ArgumentMatchers.any(),ArgumentMatchers.any(),ArgumentMatchers.any())).thenReturn(List("https://sunbird.org/test/2018-05-20.json"));
    doNothing().when(mockStorageService).closeContext()
    
    val resObj = jobApiServiceActorRef.underlyingActor.getChannelData("in.ekstep", "raw", "2018-05-20", "2018-05-20")
    resObj.responseCode should be("OK")
    val res = resObj.result.getOrElse(Map())
    val urls = res.get("files").get.asInstanceOf[List[String]];
    urls.size should be (1)
    urls.head should be ("https://sunbird.org/test/signed/2018-05-20.json")
    val periodWiseFiles = res.get("periodWiseFiles").get.asInstanceOf[Map[String,List[String]]];
    periodWiseFiles.size should be (1)
    periodWiseFiles.get("2018-05-20").get.head should be ("https://sunbird.org/test/signed/2018-05-20.json")
    
  }

  it should "get the channel data for summary rollup data" in {

    reset(mockStorageService)
    when(mockFc.getStorageService(ArgumentMatchers.any())).thenReturn(mockStorageService);
    when(mockStorageService.upload(ArgumentMatchers.any(),ArgumentMatchers.any(),ArgumentMatchers.any(),ArgumentMatchers.any(),ArgumentMatchers.any(),ArgumentMatchers.any(),ArgumentMatchers.any())).thenReturn("");
    when(mockStorageService.getSignedURL(ArgumentMatchers.any(),ArgumentMatchers.any(),ArgumentMatchers.any(),ArgumentMatchers.any())).thenReturn("https://sunbird.org/test/signed");
    when(mockStorageService.searchObjectkeys(ArgumentMatchers.any(),ArgumentMatchers.any(),ArgumentMatchers.any(),ArgumentMatchers.any(),ArgumentMatchers.any(),ArgumentMatchers.any())).thenReturn(List("https://sunbird.org/test"));
    doNothing().when(mockStorageService).closeContext()

    val resObj = jobApiServiceActorRef.underlyingActor.getChannelData("in.ekstep", "summary-rollup", "2018-05-20", "2018-05-20")
    resObj.responseCode should be("OK")
    val res = resObj.result.getOrElse(Map())
    val urls = res.get("files").get.asInstanceOf[List[String]];
    urls.size should be (1)
    urls.head should be ("https://sunbird.org/test/signed")

  }

  it should "cover all cases for summary rollup channel data" in {

    reset(mockStorageService)
    when(mockFc.getStorageService(ArgumentMatchers.any())).thenReturn(mockStorageService);
    when(mockStorageService.upload(ArgumentMatchers.any(),ArgumentMatchers.any(),ArgumentMatchers.any(),ArgumentMatchers.any(),ArgumentMatchers.any(),ArgumentMatchers.any(),ArgumentMatchers.any())).thenReturn("");
    when(mockStorageService.getSignedURL(ArgumentMatchers.any(),ArgumentMatchers.any(),ArgumentMatchers.any(),ArgumentMatchers.any())).thenReturn("");
    when(mockStorageService.searchObjectkeys(ArgumentMatchers.any(),ArgumentMatchers.any(),ArgumentMatchers.any(),ArgumentMatchers.any(),ArgumentMatchers.any(),ArgumentMatchers.any())).thenReturn(List());
    doNothing().when(mockStorageService).closeContext()

    val resObj1 = jobApiServiceActorRef.underlyingActor.getChannelData("in.ekstep", "summary-rollup", "2018-05-20", "2018-05-20")
    resObj1.responseCode should be("OK")
    val res1 = resObj1.result.getOrElse(Map())
    val urls1 = res1.get("files").get.asInstanceOf[List[String]];
    urls1.size should be (0)

    val resObj2 = jobApiServiceActorRef.underlyingActor.getChannelData("in.ekstep", "summary-rollup", "2018-05-20", "9999-05-20")
    resObj2.responseCode should be("CLIENT_ERROR")
    resObj2.params.errmsg should be("'to' should be LESSER OR EQUAL TO today's date..")

    val resObj3 = jobApiServiceActorRef.underlyingActor.getChannelData("in.ekstep", "summary-rollup", "2018-05-10", "2018-05-30")
    resObj3.responseCode should be("CLIENT_ERROR")
    resObj3.params.errmsg should be("Date range should be < 10 days")

    val resObj4 = jobApiServiceActorRef.underlyingActor.getChannelData("in.ekstep", "summary-rollup", "2018-06-20", "2018-05-30")
    resObj4.responseCode should be("CLIENT_ERROR")
    resObj4.params.errmsg should be("Date range should not be -ve. Please check your 'from' & 'to'")
  }
  
  it should "test all exception branches" in {
    import akka.pattern.ask
    val toDate = new LocalDate().toString()
    val fromDate = new LocalDate().minusDays(11).toString()
    var result = Await.result((jobApiServiceActorRef ? ChannelData("in.ekstep", "raw", fromDate, toDate, "", config)).mapTo[Response], 20.seconds)
    result.responseCode should be("CLIENT_ERROR")
    result.params.errmsg should be("Date range should be < 10 days")

    result = Await.result((jobApiServiceActorRef ? ChannelData("in.ekstep", "summary-rollup", fromDate, toDate, "", config)).mapTo[Response], 20.seconds)
    result.responseCode should be("CLIENT_ERROR")
    result.params.errmsg should be("Date range should be < 10 days")

    val request1 = """{"id":"ekstep.analytics.data.out","ver":"1.0","ts":"2016-12-07T12:40:40+05:30","params":{"msgid":"4f04da60-1e24-4d31-aa7b-1daf91c46341"},"request":{"requestedBy":"test-1","jobId":"course-progress-report","jobConfig":{"batchFilters":["TPD","NCFCOPY"],"contentFilters":{"request":{"filters":{"identifier":["do_11305960936384921612216","do_1130934466492252161819"],"prevState":"Draft"},"sort_by":{"createdOn":"desc"},"limit":10000,"fields":["framework","identifier","name","channel","prevState"]}},"reportPath":"course-progress-v2/"},"output_format":"csv"}}"""
    result = Await.result((jobApiServiceActorRef ? DataRequest(request1, "in.ekstep", config)).mapTo[Response], 20.seconds)
    result.responseCode should be("CLIENT_ERROR")

    result = Await.result((jobApiServiceActorRef ? GetDataRequest("test-tag-1", "14621312DB7F8ED99BA1B16D8B430FAC", config)).mapTo[Response], 20.seconds)
    result.responseCode should be("OK")

    result = Await.result((jobApiServiceActorRef ? DataRequestList("client-4", 2, config)).mapTo[Response], 20.seconds)
    val resultMap = result.result.get
    val jobRes = JSONUtils.deserialize[List[JobResponse]](JSONUtils.serialize(resultMap.get("jobs").get))
    jobRes.length should be(0)

  }
}
