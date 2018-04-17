package com.rdnsn.b2intgr.route;


import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.*;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.google.common.collect.ImmutableList;
import com.rdnsn.b2intgr.MainApp;
import com.rdnsn.b2intgr.dao.ProxyUrlDAO;
import com.rdnsn.b2intgr.exception.B2BadRequestException;
import com.rdnsn.b2intgr.model.ProxyUrl;
import com.rdnsn.b2intgr.util.JsonHelper;
import com.rdnsn.b2intgr.util.MirrorMap;
import org.apache.camel.*;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.component.http4.HttpMethods;

import org.apache.camel.json.simple.JsonObject;
import org.apache.camel.model.dataformat.JsonLibrary;
import org.apache.camel.model.rest.RestBindingMode;
import org.apache.camel.model.rest.RestDefinition;
import org.apache.camel.processor.aggregate.AggregationStrategy;
import org.apache.commons.fileupload.FileItem;
import org.apache.commons.fileupload.FileUploadException;
import org.apache.commons.fileupload.disk.DiskFileItemFactory;
import org.apache.commons.io.IOUtils;
import org.apache.http.HttpEntity;
import org.apache.http.HttpStatus;
import org.apache.http.util.EntityUtils;
import org.restlet.data.MediaType;
import org.restlet.engine.adapter.HttpRequest;
import org.restlet.ext.fileupload.RestletFileUpload;
import org.restlet.representation.InputRepresentation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableMap;
import com.rdnsn.b2intgr.api.*;
import com.rdnsn.b2intgr.CloudFSConfiguration;
import com.rdnsn.b2intgr.util.Constants;
import com.rdnsn.b2intgr.model.UploadData;
import com.rdnsn.b2intgr.model.UserFile;
import com.rdnsn.b2intgr.processor.AuthAgent;
import com.rdnsn.b2intgr.exception.UploadException;
import com.rdnsn.b2intgr.processor.UploadProcessor;

/**
 * Base Router
 */
public class ZRouteBuilder extends RouteBuilder {

    public static final String HTTP4_PARAMS = "?throwExceptionOnFailure=false&okStatusCodeRange=100-999";
//            "&disableStreamCache=true";

    private Logger log = LoggerFactory.getLogger(getClass());

    private final CloudFSConfiguration serviceConfig;
    private final ObjectMapper objectMapper;

    private final AuthAgent authAgent;
    private final String ppath_delete_files = "/b2api/v1/b2_delete_file_version" + HTTP4_PARAMS;
    private final String ppath_list_file_vers = "/b2api/v1/b2_list_file_versions" + HTTP4_PARAMS;
    private final String ppath_list_buckets = "/b2api/v1/b2_list_buckets" + HTTP4_PARAMS;
    private final String ppath_list_file_names = "/b2api/v1/b2_list_file_names" + HTTP4_PARAMS;
    private final String ppath_start_large_file = "/b2api/v1/b2_start_large_file" + HTTP4_PARAMS;
    private final String ppath_get_upload_part_url = "/b2api/v1/b2_get_upload_part_url" + HTTP4_PARAMS;
    private final String ppath_finish_large_file = "/b2api/v1/b2_finish_large_file" + HTTP4_PARAMS;
    private final String ppath_update_bucket = "/b2api/v1/b2_update_bucket" + HTTP4_PARAMS;

    public final URI mailURL;

    public static final String bchmodService            = "/chbktacc";
    public static final String deleteFilesService       = "/rm";
    public static final String downloadService          = "/file";
    public static final String grantDwnldService        = "/grantDwnld";
    public static final String listBucketsService       = "/list";
    public static final String listFileNamesService     = "/ls";
    public static final String listFileVersService      = "/lsvers";
    public static final String uploadFileUriService     = "/upload/{bucketId}/{author}/{destDir}";

    private MirrorMap<String, String> bucketMap;




    public static URL RESTAPI_HOST;
    public static URL RESTAPI_ENDPOINT;


    // // TODO: 2/13/18 url-encode the downloadURL
    public ZRouteBuilder(ObjectMapper objectMapper, CloudFSConfiguration serviceConfig, AuthAgent authAgent) throws URISyntaxException {
        super();
        this.objectMapper = objectMapper;
        this.serviceConfig = serviceConfig;
        this.authAgent = authAgent;
        this.mailURL = new URI(
            "smtps",
            null,
            serviceConfig.getMailConfig().getHost(),
            serviceConfig.getMailConfig().getPort(),
            "",
            String.format("username=%s&password=%s&to=%s",
                serviceConfig.getMailConfig().getUsername(),
                serviceConfig.getMailConfig().getPassword(),
                serviceConfig.getMailConfig().getRecipients()
            ),
            null
        );

        try {
            this.RESTAPI_HOST = new URL(serviceConfig.getProtocol(), serviceConfig.getHost(), serviceConfig.getPort(), "/");
            this.RESTAPI_ENDPOINT = new URL(RESTAPI_HOST, serviceConfig.getContextUri());
        } catch (MalformedURLException e) {
            log.error(e.getMessage(), e);
        }


        // enable Jackson json type converter
        getContext().getGlobalOptions().put("CamelJacksonEnableTypeConverter", "true");
        // allow Jackson json to convert to pojo types also
        getContext().getGlobalOptions().put("CamelJacksonTypeConverterToPojo", "true");
    }

    /**
     * Routes ...
     */
    public void configure() {

        final AggregationStrategy fileListResultAggregator = (Exchange original, Exchange resource) -> {

            FileResponse newBody = resource.getIn().getBody(FileResponse.class);

            if (original == null) {
                DeleteFilesResponse resp = new DeleteFilesResponse();
                resp.updateFile(newBody);
                resource.getOut().setBody(resp);
                if (newBody.getStatus() != null) {
                    resource.getOut().setHeader(Exchange.HTTP_RESPONSE_CODE, HttpStatus.SC_ACCEPTED);
                }
                return resource;
            }
            if (newBody.getStatus() != null) {
                original.getIn().setHeader(Exchange.HTTP_RESPONSE_CODE, HttpStatus.SC_ACCEPTED);
            }
            original.getIn().getBody(DeleteFilesResponse.class).updateFile(newBody);

            return original;
        };

        final AggregationStrategy fileResponseAggregator = (Exchange original, Exchange resource) -> {
            log.debug("fileResponseAggregator Headers:\n" +  resource.getIn().getHeaders().entrySet().stream().map(entry -> entry.getKey() + " ::: " + entry.getValue()).collect(Collectors.joining("\n")));
            String body = resource.getIn().getBody(String.class);
//            log.debug("fileResponseAggregator body:\n" +  body);

            final Integer code = resource.getIn().getHeader(Exchange.HTTP_RESPONSE_CODE, Integer.class);

            if (code == null || HttpStatus.SC_OK != code) {
                ErrorObject errorObject = JsonHelper.coerceClass(objectMapper, body, ErrorObject.class);
                original.getOut().copyFromWithNewBody(resource.getIn(), new ListFilesResponse().setError(errorObject));
                original.getOut().setHeader(Exchange.HTTP_RESPONSE_CODE, Optional.of(code).orElse(HttpStatus.SC_BAD_REQUEST));

            } else {

                ListFilesResponse lfResponse = JsonHelper.coerceClass(objectMapper, body, ListFilesResponse.class);

                final String bucketId = JsonHelper.coerceClass(objectMapper, original.getIn(), ListFilesRequest.class).getBucketId();
//                final String bucketId = original.getIn().getBody(ListFilesRequest.class).getBucketId();
                log.debug("bucketId: {}" , bucketId);

                lfResponse.setBucketId(bucketId);
                log.debug("lfResponse:\n\t" + lfResponse);

                // TODO: 3/6/18 Get rid of BiFunc MakeDownloadURL()
                original.getOut().setBody(lfResponse.setMakeDownloadUrl(file ->
                    buildURLString(
                        authAgent.getAuthResponse().getDownloadUrl(),
                        "file",
                        getBucketMap().getObject(bucketId), file.getFileName())
                ));

                log.debug("bucketId: {}//{}//{}" ,
                        authAgent.getAuthResponse().getDownloadUrl(),
                        "file",
                        getBucketMap().getObject(bucketId));
            }

            original.getOut().removeHeader(Constants.AUTHORIZATION);
            return original;
        };

        final Processor createPostFileList = (exchange) -> {
            ListFilesRequest lfr = exchange.getIn().getBody(ListFilesRequest.class);

            log.debug("got LFR: {}", lfr.toString());

            exchange.getOut().setHeader(Constants.AUTHORIZATION, exchange.getIn().getHeader(Constants.AUTHORIZATION, String.class));
            exchange.getOut().setHeader(Exchange.HTTP_METHOD, HttpMethods.POST);
            exchange.getOut().setBody(lfr);

        };

        final Processor createPost = (Exchange exchange) -> {

            final AuthResponse auth = exchange.getIn().getHeader(Constants.AUTH_RESPONSE, AuthResponse.class);
            exchange.getOut().setHeader(Constants.AUTHORIZATION, auth.getAuthorizationToken());
            exchange.getOut().setHeader(Exchange.HTTP_METHOD, HttpMethods.POST);
            exchange.getOut().setBody(objectMapper.writeValueAsString(ImmutableMap.of(
                "accountId", auth.getAccountId(),
                "bucketTypes", ImmutableList.of("allPrivate", "allPublic")
            )));
        };

        onException(UploadException.class)
            .asyncDelayedRedelivery()
            .maximumRedeliveries(serviceConfig.getMaximumRedeliveries())
            .redeliveryDelay(serviceConfig.getRedeliveryDelay())
                .useExponentialBackOff()
                .backOffMultiplier(serviceConfig.getBackOffMultiplier())
            .to("direct:mail")
            .handled(true);


//        onException(org.apache.camel.http.common.HttpOperationFailedException.class).process(exchange -> {
////            ErrorObject err = exchange.getOut().getBody(ErrorObject.class);
////            exchange.getOut().setHeader(Exchange.HTTP_RESPONSE_CODE, err.getStatus());
//            exchange.getOut().setBody("{ err: \"v\"}");
//        }).handled(true);

        onException(B2BadRequestException.class).process(exchange -> {
            ErrorObject err = exchange.getOut().getBody(ErrorObject.class);
            exchange.getOut().setHeader(Exchange.HTTP_RESPONSE_CODE, err.getStatus());
            exchange.getOut().setBody(err);
        }).handled(true);

        RestDefinition rest = defineRestServer();




        // Upload a File
        rest.post("/uptest/{bucketId}/{author}/{destDir}")
                .consumes("multipart/form-data")
                .route().process(exchange -> {
            String sent = IOUtils.toString(exchange.getIn().getBody(InputStream.class), StandardCharsets.UTF_8);

            // RAW DUMP
            // Echo
            exchange.getOut().copyFromWithNewBody(exchange.getIn(), sent);
        });


        from("direct:mail").id("mailman")
            .setHeader("subject", constant("BackBlaze Upload Failed"))
            .to(mailURL.toString());

        // Authenticate
        from("direct:auth")
                .enrich("bean:authAgent?method=getAuthResponse", authAgent)
                .end();

        // Replies -> List of buckets
        from("direct:rest.list_buckets")
                .to("direct:auth", "direct:list_buckets")
                .end();

        // Replies -> List Files in base bucket
        from("direct:rest.list_files")
                .to("direct:auth", "direct:list_files")
                .end();

        // Replies -> List File Versions
        from("direct:rest.list_filevers")
                .to("direct:auth", "direct:list_filevers")
                .end();

        // Replies -> Delete Files
        from("direct:rest.rm_files")
                .to("direct:auth", "direct:rm_files")
                .end();

        // Replies -> HREF to resource
        from("direct:rest.multipart")
                .process(new SaveLocally())
                // Send to b2
                .wireTap("direct:b2upload")
                .end()

                .process(new ReplyProxyUrls())
                .end();


        from("direct:b2upload").routeId("atomic_upload_list")
                .to("direct:auth")
                .split(body().method("getFiles"))
//                .split(new ListSplitExpression())
                .to("vm:sub")
                .end();

        from("vm:sub")
                .choice()
                .when(new FileSizePredicate(authAgent.getAuthResponse().getRecommendedPartSize()) )
                    .to("direct:b2send_large")
                .otherwise()
                    .threads(serviceConfig.getPoolSize(), serviceConfig.getMaxPoolSize())
                    .to("direct:b2send")
                .endChoice()
                //                .delay(500)
                .process(new PersistMapping())
                .end();

        from("direct:b2send_large").routeId("atomic_large_upload")
                .errorHandler(noErrorHandler())
//                .setHeader(Constants.USER_FILE, body())
                .to( "direct:start_large_upload", "direct:make_upload_parts", "direct:finish_large_file")
//                .log(LoggingLevel.DEBUG, log, body().toString())
//                .process(new PersistMapping())
                .end();


        from("direct:make_upload_parts")
            .split(new ChunkFileExpression(),  (Exchange original, Exchange resource) -> {


                if (original == null ) {

                    FilePart filePart = resource.getIn().getBody(FilePart.class);
                    Integer numParts = resource.getIn().getHeader("numParts", Integer.class);


                    log.debug("filePart: {}", filePart);
                    log.debug("Resource.IN -> numParts: {}" , numParts);

                    FilePart[] parts = new FilePart[numParts];
                    parts[filePart.getPartNumber() - 1] = filePart;

                    resource.getIn().setBody(parts);

                    return resource;
                }

                FilePart filePart = resource.getIn().getBody(FilePart.class);
                FilePart[] parts = original.getIn().getBody(FilePart[].class);

                parts[filePart.getPartNumber() - 1] = filePart;
                original.getOut().copyFromWithNewBody(original.getIn(), parts);

                return original;
            })
//                .shareUnitOfWork()
//                .parallelProcessing()
            .to("direct:upload_part")
        .end();


        from("direct:upload_part")
                .process(exchange -> {

                    final FilePart partFile = exchange.getIn().getBody(FilePart.class);

                    final ProducerTemplate producer = exchange.getContext().createProducerTemplate();
                    final StartLargeUploadResponse uploadResponse = exchange.getIn().getHeader("startLargeUploadResponse", StartLargeUploadResponse.class);

                    String getUploadUrlURL = getHttp4Proto(authAgent.getApiUrl()) + ppath_get_upload_part_url;
                    log.debug("getUploadUrlURL: {}", getUploadUrlURL);

                    final Message gotUploadUrlURL = producer.send(getUploadUrlURL, innerExchg -> {
                        JsonObject jsonObj = new JsonObject();
                        jsonObj.put("fileId", uploadResponse.getFileId());
                        innerExchg.getIn().setHeader(Constants.AUTHORIZATION, authAgent.getAuthResponse().getAuthorizationToken());
                        innerExchg.getIn().setHeader(Exchange.HTTP_METHOD, HttpMethods.POST);
                        innerExchg.getIn().setBody(jsonObj.toJson());
                    }).getOut();

                    /*
{
  "authorizationToken": "3_20160409004829_42b8f80ba60fb4323dcaad98_ec81302316fccc2260201cbf17813247f312cf3b_000_uplg",
  "fileId": "4_ze73ede9c9c8412db49f60715_f100b4e93fbae6252_d20150824_m224353_c900_v8881000_t0001",
  "uploadUrl": "https://pod-000-1016-09.backblaze.com/b2api/v1/b2_upload_part/4_ze73ede9c9c8412db49f60715_f100b4e93fbae6252_d20150824_m224353_c900_v8881000_t0001/0037"
}
                     */

                    Integer code = gotUploadUrlURL.getHeader(Exchange.HTTP_RESPONSE_CODE, Integer.class);
                    log.info("gotUploadUrlURL RESPONSE_CODE:{ '{}' partFileId: '{}'}", code, uploadResponse.getFileId());


                    if (code != null && HttpStatus.SC_OK == code) {

                        Map<String, String> startValsResponse  = JsonHelper.coerceClass(objectMapper, gotUploadUrlURL, HashMap.class);

                        assert(startValsResponse.get("fileId").equals(partFile.getFileId()));

                        log.debug("partUploadUrl: {}", startValsResponse.get("uploadUrl"));

                        partFile.setUploadUrl(startValsResponse.get("uploadUrl"));
                        partFile.setFileId(startValsResponse.get("fileId"));
                        partFile.setAuthorizationToken(startValsResponse.get("authorizationToken"));

                        final JsonObject partUploadObj = new JsonObject();
                        partUploadObj.put("fileId", partFile.getFileId());


                        log.debug("uplPart authorizationToken {} " , partFile.getAuthorizationToken());
                        log.debug("uplPartReq {} " , partUploadObj.toString());


                        final ProducerTemplate uploader = exchange.getContext().createProducerTemplate();
                        final Message uploaderOut = uploader.send(getHttp4Proto(partFile.getUploadUrl()), innerExchg -> {
                            innerExchg.getIn().setHeader(Constants.AUTHORIZATION, partFile.getAuthorizationToken());
                            innerExchg.getIn().setHeader(Exchange.HTTP_METHOD, HttpMethods.POST);
                            innerExchg.getIn().setHeader(Constants.X_BZ_PART_NUMBER, partFile.getPartNumber());
                            innerExchg.getIn().setHeader(Constants.CONTENT_LENGTH, partFile.getContentLength());
                            innerExchg.getIn().setHeader(Constants.X_BZ_CONTENT_SHA1, partFile.getContentSha1());
                            innerExchg.getIn().setBody(partFile.getData().array());

                            partFile.done();
                        }).getOut();


                        code = uploaderOut.getHeader(Exchange.HTTP_RESPONSE_CODE, Integer.class);
                        log.debug("uploaderOut RESPONSE_CODE:{ '{}' partFile: '{}'}", code, partFile);

                        if (code != null && HttpStatus.SC_OK == code) {

                            Map<String, Object> uplPartResponse  = JsonHelper.coerceClass(objectMapper, uploaderOut, HashMap.class);
                            assert(uplPartResponse.get("fileId").equals(partFile.getFileId()));
                            exchange.getOut().copyFromWithNewBody(exchange.getIn(), partFile);

                        } else {
                            ErrorObject errorObject = JsonHelper.coerceClass(objectMapper, uploaderOut, ErrorObject.class);
                            log.debug("errorObject: {} ", errorObject);
                            partFile.setError(errorObject);
                            throw new UploadException("Response code fail (" + code + ") partFile '" + partFile + "' not uploaded");
                        }
                    } else {
                        ErrorObject errorObject = JsonHelper.coerceClass(objectMapper, gotUploadUrlURL, ErrorObject.class);
                        log.debug("errorObject: {} ", errorObject);
                        partFile.setError(errorObject);
                        throw new UploadException("Response code fail (" + code + ") partFile '" + partFile + "' not uploaded");
                    }
                })
                .end();

        from("direct:start_large_upload")
                .process(exchange -> {
                    final AuthResponse auth = exchange.getIn().getHeader(Constants.AUTH_RESPONSE, AuthResponse.class);

                    final ProducerTemplate startLgProducer = exchange.getContext().createProducerTemplate();
                    final UserFile userFile = exchange.getIn().getBody(UserFile.class);
                    exchange.getOut().setBody(userFile);


                    String start_large_url = getHttp4Proto(authAgent.getApiUrl()) + ppath_start_large_file;
                    log.debug("start_large_url: {}", start_large_url);
                    final JsonObject startLargeFileJsonObj = new JsonObject(ImmutableMap.<String, String>builder()
                            .put("fileName", userFile.getRelativePath())
                            .put("contentType", userFile.getContentType())
                            .put("bucketId", userFile.getBucketId())
                            .build());

                    log.debug("large_url POST: {}", startLargeFileJsonObj.toJson());

                    final Message startLgResponseOut = startLgProducer.send(start_large_url, innerExchg -> {

                        log.debug("innerExchg.getIn() Headers: {}", innerExchg.getIn().getHeaders().entrySet().stream().map( entry -> String.format("name: %s%nvalue: %s", entry.getKey(), "" + entry.getValue())).collect(Collectors.toList()));

                        innerExchg.getIn().setHeader(Constants.AUTHORIZATION, auth.getAuthorizationToken());
                        innerExchg.getIn().setHeader(Exchange.HTTP_METHOD, HttpMethods.POST);
                        innerExchg.getIn().setBody(startLargeFileJsonObj.toJson());
                    }).getOut();

                    final Integer code = startLgResponseOut.getHeader(Exchange.HTTP_RESPONSE_CODE, Integer.class);

                    log.info("start_large_upload RESPONSE_CODE:{ '{}' XBzFileName: '{}'}", code, userFile.getRelativePath());

                    if (code != null && HttpStatus.SC_OK == code) {
                            StartLargeUploadResponse uploadResponse = coerceClass(startLgResponseOut, StartLargeUploadResponse.class);
                            exchange.getOut().copyFromWithNewBody(startLgResponseOut, userFile);
                            exchange.getOut().setHeader("startLargeUploadResponse", uploadResponse);
//                    } else {
//                        ErrorObject errorObject = JsonHelper.coerceClass(objectMapper, responseOut, ErrorObject.class);
//                        log.debug("errorObject: {} ", errorObject);
//                        userFile.setError(errorObject);
//                        throw new UploadException("Response code fail (" + code + ") File '" + userFile.getRelativePath() + "' not uploaded");
                    }
                })
        .end();

        from("direct:finish_large_file")
                .process(exchange -> {

                    final StartLargeUploadResponse uploadResponse = exchange.getIn().getHeader("startLargeUploadResponse", StartLargeUploadResponse.class);


                    List<FilePart> parts = Arrays.asList(exchange.getIn().getBody(FilePart[].class));

                    if (  parts.stream().map(part -> ! part.isUnread()).reduce(true, (a, b) -> a && b) ) {
                        parts.get(0).getFileChannel().close();
                        List shas = new ArrayList(parts.size());
                        parts.forEach(part -> {
                            try {
                                shas.add(part.getContentSha1());
                            } catch (IOException e) {
                                log.error(e.getMessage(), e);
                            }
                        });

                        final JsonObject finishUploadBody = new JsonObject();
                        finishUploadBody.put("fileId", uploadResponse.getFileId());
                        finishUploadBody.put("partSha1Array", shas);

                        log.debug("finishUploadBody {} " , finishUploadBody.toString());

                        final ProducerTemplate uploader = exchange.getContext().createProducerTemplate();
                        final Message uploaderOut = uploader.send(getHttp4Proto(authAgent.getApiUrl()) + ppath_finish_large_file + HTTP4_PARAMS, innerExchg -> {
                            innerExchg.getIn().setHeader(Constants.AUTHORIZATION, authAgent.getAuthResponse().getAuthorizationToken());
                            innerExchg.getIn().setHeader(Exchange.HTTP_METHOD, HttpMethods.POST);
                            innerExchg.getIn().setBody(finishUploadBody.toJson());
                        }).getOut();

                        Integer code = uploaderOut.getHeader(Exchange.HTTP_RESPONSE_CODE, Integer.class);
                        log.info("uploaderOut RESPONSE_CODE:{ '{}' finishUpload: '{}'}", code, parts.get(0).getFileId());
                        if (code != null && HttpStatus.SC_OK == code) {
                            UploadFileResponse finishUploadResponse = JsonHelper.coerceClass(objectMapper, uploaderOut, UploadFileResponse.class);
                            exchange.getOut().copyFromWithNewBody(exchange.getIn(), finishUploadResponse);
                        } else {
                            ErrorObject errorObject = JsonHelper.coerceClass(objectMapper, uploaderOut, ErrorObject.class);
                            exchange.getOut().copyFromWithNewBody(uploaderOut, errorObject);

                            log.error("errorObject: {} ", errorObject);
                            throw new UploadException("Response code fail (" + code + ") finishUploadBody '" + finishUploadBody + "' not uploaded");
                        }
                    }
                })
        .end();

        from("direct:b2send").routeId("atomic_upload_file")
                .errorHandler(noErrorHandler())
                .process(new UploadProcessor(serviceConfig, objectMapper))
                .end();

        from("direct:list_buckets")
                .process(createPost)
                .enrich(
                    getHttp4Proto(authAgent.getApiUrl()) + ppath_list_buckets, (Exchange original, Exchange resource) -> {

                            final Integer code = resource.getIn().getHeader(Exchange.HTTP_RESPONSE_CODE, Integer.class);
                            log.error("HttpStatus: {} ", code);

                            if (code == null || HttpStatus.SC_OK != code) {
                                ErrorObject errorObject = JsonHelper.coerceClass(objectMapper, resource.getIn(), ErrorObject.class);
                                original.getOut().copyFromWithNewBody(resource.getIn(), errorObject);
                                original.getOut().setHeader(Exchange.HTTP_RESPONSE_CODE, Optional.of(code).orElse(HttpStatus.SC_BAD_REQUEST));
                                log.error("errorObject: {} ", errorObject);
                            } else {
                                BucketListResponse buckets = coerceClass(resource.getIn(), BucketListResponse.class);
                                log.debug("Bucket access update response: {}", buckets);
                                original.getOut().copyFromWithNewBody(resource.getIn(), buckets);

                                // Update the bucket map on each call
                                setBucketMap(new MirrorMap<String,String>(buckets.getBuckets().stream().collect(Collectors.toMap(B2Bucket::getBucketId, B2Bucket::getBucketName))));
                            }
                        return original;
                    })
                .end();

        from("direct:list_files")
                .process(createPostFileList)
                .marshal().json(JsonLibrary.Jackson)
                .enrich(
                    getHttp4Proto(authAgent.getApiUrl()) + ppath_list_file_names, fileResponseAggregator)
                .end();

        from("direct:list_filevers")
                .process(createPostFileList)
                .marshal().json(JsonLibrary.Jackson)
                .enrich(getHttp4Proto(authAgent.getApiUrl()) + ppath_list_file_vers, fileResponseAggregator)
                .end();

        from("direct:rest.file_proxy")
                .to("direct:show");

        from("direct:rest.dir_auth")
                .to("direct:auth", "direct:dir_auth");

        from("direct:rest.bchmod")
                .to("direct:auth", "direct:btouch_remote");

        from("direct:btouch_remote")
                .process(exchange -> {
                    TouchBucketRequest bt = exchange.getIn().getBody(TouchBucketRequest.class);
                    bt.setAccountId(serviceConfig.getRemoteAccountId());

                    exchange.getOut().setHeader(Constants.AUTHORIZATION, exchange.getIn().getHeader(Constants.AUTHORIZATION, String.class));
                    exchange.getOut().setHeader(Exchange.HTTP_METHOD, HttpMethods.POST);
                    exchange.getOut().setBody(bt.toString());
                })

                .enrich(getHttp4Proto(authAgent.getApiUrl()) + ppath_update_bucket, (Exchange original, Exchange resource) -> {
                    final Integer code = resource.getIn().getHeader(Exchange.HTTP_RESPONSE_CODE, Integer.class);

                    if (code == null || HttpStatus.SC_OK != code) {
                        ErrorObject errorObject = JsonHelper.coerceClass(objectMapper, resource.getIn(), ErrorObject.class);
                        original.getOut().copyFromWithNewBody(resource.getIn(), errorObject);
                        original.getOut().setHeader(Exchange.HTTP_RESPONSE_CODE, Optional.of(code).orElse(HttpStatus.SC_BAD_REQUEST));
                        log.error("errorObject: {} ", errorObject);
                    } else {

                        B2Bucket bucket = coerceClass(resource.getIn(), B2Bucket.class);
                        log.debug("Bucket access update response: {}", bucket);
                        original.getOut().copyFromWithNewBody(resource.getIn(), bucket);
                    }
                    return original;
                })
            .end();

        from("direct:dir_auth")
            .process((Exchange exchange) -> {

                DirectoryAccessRequest bt = exchange.getIn().getBody(DirectoryAccessRequest.class);
                String authToken = exchange.getIn().getHeader(Constants.AUTHORIZATION, String.class);

                exchange.getOut().setHeader(Constants.AUTHORIZATION, authToken);
                exchange.getOut().setBody(bt);

                log.debug("request body: {}", bt);
            })
                .marshal().json(JsonLibrary.Jackson)
                .enrich(getHttp4Proto(authAgent.getApiUrl()) + "/b2api/v1/b2_get_download_authorization?throwExceptionOnFailure=false&okStatusCodeRange=100",(Exchange original, Exchange resource) -> {
                    final Integer code = resource.getIn().getHeader(Exchange.HTTP_RESPONSE_CODE, Integer.class);

                    if (code == null || HttpStatus.SC_OK != code) {
                        ErrorObject errorObject = coerceClass(resource.getIn(), ErrorObject.class);
                        original.getIn().copyFromWithNewBody(resource.getIn(), errorObject);
                        original.getOut().setHeader(Exchange.HTTP_RESPONSE_CODE, Optional.of(code).orElse(HttpStatus.SC_BAD_REQUEST));
                        log.error("errorObject: {} ", errorObject);
                    } else {
                        original.getIn().copyFromWithNewBody(resource.getIn(), coerceClass(resource.getIn(), HashMap.class));
                    }
                return original;
            })
        .end();


        from("direct:show")
            .process((Exchange exchange) -> {

            HttpRequest request
                    = exchange.getIn().getHeader("CamelRestletRequest", HttpRequest.class);

            // /[serviceContextUri][servicePath][partialPath]
            // [/cloudfs/api/v1] [/file] [/top/site/images/v2/Flag_of_Jamaica.png]
            String uri = request.getHttpCall().getRequestUri();
            String ctx = serviceConfig.getContextUri() + downloadService + "/";

            String ppath = uri.substring(uri.indexOf(ctx) + ctx.length());
            File file = new File(serviceConfig.getDocRoot() + File.separatorChar + ppath);

            if (! file.exists()) {
                log.info("ppath: {} ", ppath);

                try (ProxyUrlDAO proxyMapUpdater = getProxyUrlDao()) {
                    String needleKey = new URL(ZRouteBuilder.RESTAPI_HOST, uri).toExternalForm();
                    ProxyUrl actual = proxyMapUpdater.getProxyUrl(
                        new ProxyUrl().setProxy(ppath)
                            // TODO: 4/5/18 Add bucketId to the entire Proxy lookup
                            // .setBucketId()
                    );

                    log.debug("Found proxy-Actual: {}", actual);
                    if (actual != null) {
                        exchange.getOut().setHeader(Constants.LOCATION, actual.getActual());
                        exchange.getOut().setHeader(Exchange.HTTP_RESPONSE_CODE, 301);
                    }
                    else {
                        throw makeBadRequestException("Invalid URL.", exchange, "Neither file nor mapping exists." , 400);
                    }
                }
            }
            else {
                InputStream is = new BufferedInputStream(new FileInputStream(file));
                String mimeType = URLConnection.guessContentTypeFromStream(is);
                is.close();

                exchange.getOut().setHeader(Exchange.CONTENT_TYPE, mimeType);
                exchange.getOut().setHeader(Constants.CONTENT_LENGTH, file.length());
                exchange.getOut().setBody(file);
            }
        })
        .end();
        from("direct:rm_files")
            .split(new Expression() {
                @Override
                @SuppressWarnings("unchecked")
                public <T> T evaluate(Exchange exchange, Class<T> type) {
                    Message IN = exchange.getIn();
                    DeleteFilesRequest body = exchange.getIn().getBody(DeleteFilesRequest.class);

                    String authToken = exchange.getIn().getHeader(Constants.AUTHORIZATION, String.class);
                    IN.removeHeaders("*");

                    IN.setHeader(Constants.AUTHORIZATION, authToken);
//                    IN.setHeader(Exchange.HTTP_METHOD, HttpMethods.POST);

                    log.debug("headers: {}", IN.getHeaders().entrySet());
                    return (T) body.getFiles().iterator();
                }
            }, fileListResultAggregator)
            .to("vm:delete")
            .end();

        from("direct:remove_proxy")
                .process((Exchange exchange) -> {
                    FileResponse fileIn = exchange.getIn().getBody(FileResponse.class);

                    if (fileIn.getStatus() == null ) {
                        try (ProxyUrlDAO proxyMapUpdater = getProxyUrlDao()) {

                            log.debug("fileIn.getFileName(): {} ", fileIn.getFileName());
                            log.debug("fileIn.getFileId(): {} ", fileIn.getFileId());


                            proxyMapUpdater.deleteMapping(
                                    new ProxyUrl()
                                            .setBucketId(fileIn.getBucketId())
                                            .setFileId(fileIn.getFileId())
                                            .setProxy(fileIn.getFileName()));

                        } catch (Exception e) {
                            throw makeBadRequestException(e, exchange, "DB update error.", 500);
                        }
                    }

                })
                .end();

        from("vm:delete")
            // Convert to JSON to be Post-body
            .marshal().json(JsonLibrary.Jackson)
//            .threads(serviceConfig.getPoolSize(), serviceConfig.getMaxPoolSize())
            .enrich(
                getHttp4Proto(authAgent.getApiUrl()) + ppath_delete_files, (Exchange original, Exchange resource) -> {

//                    log.debug("enrich headers: {}", original.getIn().getHeaders().entrySet());

//                    log.debug("postedData: {} ", original.getIn().getBody(String.class));
//                    log.debug("postedData Out: {} ", original.getOut().getBody(String.class));

                    final Integer code = resource.getIn().getHeader(Exchange.HTTP_RESPONSE_CODE, Integer.class);
                    log.debug("HttpStatus: {}", code);

                    FileResponse postedData = new FileResponse();

                    if (code == null || HttpStatus.SC_OK != code) {
                        ErrorObject errorObject = JsonHelper.coerceClass(objectMapper, resource.getIn(), ErrorObject.class);
                        log.debug("errorObject: " + errorObject);
                        postedData.setError(errorObject);
                        original.getOut().setHeader(Exchange.HTTP_RESPONSE_CODE, HttpStatus.SC_ACCEPTED);
                    }
                    else {
                        postedData = JsonHelper.coerceClass(objectMapper, original.getIn(), FileResponse.class);
                        log.debug("postedData: {} ", postedData );
                    }

                    original.getOut().setBody(postedData);
                    return original;
                }

            ).outputType(FileResponse.class).wireTap("direct:remove_proxy")
            .end();
    }

    public static final Pattern httpPattern = Pattern.compile("(https{0,1})(.+)");

    public static String getHttp4Proto(String url) {
        String str = url;
        Matcher m = httpPattern.matcher(url);
        if (m.find()) {
            str = m.replaceFirst("$1" + "4$2");
        }
        return str;
    }

    public void setBucketMap(MirrorMap<String,String> bucketMap) {
        this.bucketMap = bucketMap;
    }

    public MirrorMap<String, String> getBucketMap() {
        return bucketMap;
    }

    // TODO: 2/10/18 save URL mapping to DB or file
    class ReplyProxyUrls implements Processor {
        @Override
        public void process(Exchange exchange) throws IOException {

            UploadData obj = exchange.getIn().getBody(UploadData.class);
            exchange.getOut().setBody(objectMapper.writeValueAsString(obj.getFiles())); //.stream().map(usrf -> usrf).collect(Collectors.toList()));
//            exchange.getOut().setBody(obj.getFiles()); //.stream().map(usrf -> usrf).collect(Collectors.toList()));
        }
    }

    public <T> T coerceClass(Message rsrcIn, Class<T> type) {
        return JsonHelper.coerceClass(objectMapper, rsrcIn, type);
    }

    private RestDefinition defineRestServer() {
        /**
         * Configure local Rest server
         */
        restConfiguration().component("restlet").host(serviceConfig.getHost()).port(serviceConfig.getPort())
                .componentProperty("urlDecodeHeaders", "true").skipBindingOnErrorCode(false)
                .dataFormatProperty("prettyPrint", "true").componentProperty("chunked", "true");

        return rest(serviceConfig.getContextUri()).id("B2IntgrRest")
                .produces("application/json")

                // Upload a File
                .post(uploadFileUriService).id("UploadFiles")
                .description("Upload (and revise) files. Uploading to the same name and path results in creating a new <i>version</i> of the file.")
                .bindingMode(RestBindingMode.off)
                .consumes("multipart/form-data")
//                .produces("application/json")
                .to("direct:rest.multipart")

                // List Buckets
                .get(listBucketsService).id("ListBuckets").outType(BucketListResponse.class)
                .description("List buckets")
                .bindingMode(RestBindingMode.auto)
                .produces("application/json")
                .to("direct:rest.list_buckets")

                // List Files
                .post(listFileNamesService).type(ListFilesRequest.class).outType(ListFilesResponse.class)
                .description("List files")
                .bindingMode(RestBindingMode.auto)
                .consumes("application/json")
                .produces("application/json")
                .to("direct:rest.list_files")

                // List File Versions
                .post(listFileVersService).type(ListFilesRequest.class).outType(ListFilesResponse.class)
                .description("List file and versions thereof")
                .bindingMode(RestBindingMode.auto)
                .produces("application/json")
                .to("direct:rest.list_filevers")

                .post(deleteFilesService).type(DeleteFilesRequest.class).outType(DeleteFilesResponse.class)
                .bindingMode(RestBindingMode.auto)
                .produces("application/json")
                .to("direct:rest.rm_files")

                // Download
                .get(downloadService + "?matchOnUriPrefix=true")
                .description("File reference")
                .bindingMode(RestBindingMode.off)
                .to("direct:rest.file_proxy")

                // chmodDir
                .post(grantDwnldService).type(DirectoryAccessRequest.class)
                .bindingMode(RestBindingMode.auto)
                .produces("application/json")
//                .consumes("application/json")
                .to("direct:rest.dir_auth")

                // chmod on bucket
                .put(bchmodService).type(TouchBucketRequest.class).outType(BucketListResponse.class)
                .description("Update bucket")
                .bindingMode(RestBindingMode.auto)
                .produces("application/json")
                .to("direct:rest.bchmod");


    }

    private class SaveLocally implements Processor {

        @Override
        public void process(Exchange exchange) throws B2BadRequestException {

            final Message messageIn = exchange.getIn();
            log.debug("upl headers: {}", messageIn.getHeaders().entrySet().stream().map(entry -> entry.getKey() + ": " + entry.getValue()).collect(Collectors.joining(" <> ")));
            MediaType mediaType = messageIn.getHeader(Exchange.CONTENT_TYPE, MediaType.class);


            InputRepresentation representation = new InputRepresentation(messageIn.getBody(InputStream.class), mediaType);

            log.debug("representation: {}", representation);

            String contextId = null;
            String bucketId = null;
            String author = null;
            try {
                contextId = URLDecoder.decode(messageIn.getHeader("destDir", String.class), Constants.UTF_8);
                bucketId = URLDecoder.decode(messageIn.getHeader("bucketId", String.class), Constants.UTF_8);
                author = URLDecoder.decode(messageIn.getHeader("author", String.class), Constants.UTF_8);

                if (contextId == null) {
                    contextId = "";
                } else {
                    contextId = contextId.replaceAll(serviceConfig.getCustomSeparator(), "/");
                }

            } catch (UnsupportedEncodingException e) {
                throw makeBadRequestException(e, exchange, "Error parsing input", 500);
            }

            log.debug("bucketId: {}", bucketId);
            log.debug("author: {}", author);

            List<FileItem> items = null;
            try {
                items = new RestletFileUpload(new DiskFileItemFactory()).parseRepresentation(representation);
            } catch (FileUploadException e) {
                throw makeBadRequestException(e, exchange, "Error parsing input", 500);
            }

            if (!items.isEmpty()) {

                UploadData uploadData = new UploadData();

                for (FileItem item : items) {
                    log.debug("getContentType: {}", item.getContentType());
                    log.debug("getName: {}", item.getName());
                    log.debug("getFieldName: {}", item.getFieldName());
                    log.debug("getSize: {}", item.getSize());
                    log.debug("toString: {}", item.toString());

                    if (item.isFormField()) {
                        uploadData.putFormField(item.getFieldName(), item.getString());
                    } else {
                        String pathFromUser = contextId + File.separatorChar + item.getFieldName();
                        String partialPath = null;
                        try {
                            partialPath = URLEncoder.encode(pathFromUser + File.separatorChar + item.getName(), Constants.UTF_8)
                                    .replaceAll("%2F", "/");
                        } catch (UnsupportedEncodingException e) {
                           log.error(e.getMessage(), e);
                        }

                        log.debug("partialPath: {}", partialPath);
                        Path destination = Paths.get(serviceConfig.getDocRoot() + File.separatorChar + partialPath);
                        log.debug("destination: {}", destination);


                        try {
                            Files.createDirectories(destination.getParent());
                        } catch (IOException e) {
                           log.error(e.getMessage(), e);
                        }

                        try {
                            Files.copy(item.getInputStream(), destination, StandardCopyOption.REPLACE_EXISTING);
                        } catch (IOException e) {
                           log.error(e.getMessage(), e);
                        }
                        log.debug("copied: {} to ", item.getFieldName(), destination);

                        UserFile userFile = new UserFile(destination)
                                .setContentType(item.getContentType())
                                .setBucketId(bucketId)
                                .setRelativePath(partialPath);

                        log.debug("userFile: {}", userFile);

                        userFile.setAuthor(author);

                        userFile.setDownloadUrl(buildURLString(ZRouteBuilder.RESTAPI_ENDPOINT, "file", partialPath));
                        uploadData.addFile(userFile);

                        item.delete();
                    }
                }
                try (ProxyUrlDAO proxyMapUpdater = getProxyUrlDao()) {
                    uploadData.getFiles().forEach(aUserFile -> {
                        aUserFile.setTransientId((Long) proxyMapUpdater.saveOrUpdateMapping(
                                new ProxyUrl()
                                        .setProxy(aUserFile.getRelativePath())
                                        .setFileId(aUserFile.getFileId())
                                        .setSha1(aUserFile.getSha1())
                                        .setBucketId(aUserFile.getBucketId())
                                        .setContentType(aUserFile.getContentType())
                                        .setSize(aUserFile.getSize())
                        ));
                    });
                } catch (Exception e) {
                    throw makeBadRequestException(e, exchange, "DB update error.", 400);
                }
                exchange.getOut().setBody(uploadData);
            }
        }
    }

    public static B2BadRequestException makeBadRequestException(String msg, Exchange exchange, String submsg, int status) {
        exchange.getOut().setBody(new ErrorObject()
                .setMessage(msg)
                .setCode(submsg)
                .setStatus(status));

        return new B2BadRequestException(msg);
    }

    public static B2BadRequestException makeBadRequestException(Exception e, Exchange exchange, String submsg, int status) {
        return makeBadRequestException(e.getMessage(), exchange, submsg, status);
    }

    private URL buildURL(URL endpointURL, String... paths) {
        try {
            return new URL(endpointURL , endpointURL.getPath() + "/" + String.join("/", paths));
        } catch (MalformedURLException e) {
            throw new RuntimeException(e.getCause());
        }
    }

    private String buildURLString(URL endpointURL, String... paths) {
        return buildURL(endpointURL , paths).toString();
    }

    private String buildURLString(String restapiEndpoint, String... paths) {
        try {
            return buildURLString(new URL(restapiEndpoint), paths);
        } catch (MalformedURLException e) {
            throw new RuntimeException(e.getCause());
        }
    }

    private ProxyUrlDAO getProxyUrlDao() {
        return new ProxyUrlDAO(serviceConfig.getNeo4jConf(), objectMapper);
    }

    private class ChunkFileExpression implements Expression {
        @Override
        @SuppressWarnings("unchecked")
        public <T> T evaluate(Exchange exchange, Class<T> type) {

            UserFile userFile = exchange.getIn().getBody(UserFile.class);

            log.debug(userFile.toString());

            // TODO: 3/21/18 Replace with b2 suggested partsize
            int maxMemSize = authAgent.getAuthResponse().getAbsoluteMinimumPartSize();

            Path path = Paths.get(userFile.getFilepath());

            long fsize = path.toFile().length();

            int chunkSize = (fsize < maxMemSize) ? (int) fsize : maxMemSize;

            final ArrayList<FilePart> parts = new ArrayList<FilePart>((int) fsize/chunkSize);
            final FileChannel fileChannel;

            long start = 0;
            int partNo = 1;

            try {
                fileChannel = FileChannel.open(path);

                for ( long remaining = fsize; remaining > 0; start = chunkSize * partNo++  ) {

                    if ( remaining < chunkSize) chunkSize = (int) remaining;

                    remaining -= chunkSize;

                    log.debug("start: " + start + ", chunkSize: " + chunkSize + ", partNo: " + partNo);
                    parts.add(new FilePart( fileChannel, start, chunkSize, partNo ));
                }
            } catch (IOException e) {
               log.error(e.getMessage(), e);
            }
            log.debug("parts: " + parts);
            exchange.getIn().setHeader("numParts", partNo - 1);
            return (T) parts;
        }
    }


    private class ListSplitExpression implements Expression {
        @Override
        @SuppressWarnings("unchecked")
        public <T> T evaluate(Exchange exchange, Class<T> type) {
            return (T) exchange.getIn().getBody(UploadData.class).getFiles().iterator();
        }
    }


    private class FileSizePredicate implements Predicate {
        private final long limit;
        public FileSizePredicate() { super(); limit = Constants.GIG_ON_DISK * 5; }
        public FileSizePredicate(long limit) { super(); this.limit = limit; }

        @Override
        public boolean matches(Exchange exchange) {
            return exchange.getIn().getBody(UserFile.class).getSize() > limit;
        }
    }

    private class PersistMapping implements Processor {

        @Override
        public void process(Exchange exchange) {
            UploadFileResponse uploadResponse = exchange.getIn().getBody(UploadFileResponse.class);

            UserFile uf = exchange.getIn().getHeader(Constants.USER_FILE, UserFile.class);

            try (ProxyUrlDAO proxyMapUpdater = getProxyUrlDao()) {
                Long id = (Long) proxyMapUpdater.saveOrUpdateMapping(
                    new ProxyUrl()
//                        .setProxy(uf.getDownloadUrl())
                        .setProxy(uf.getRelativePath())
                            .setFileId(uploadResponse.getFileId())

                            .setBucketId(uploadResponse.getBucketId())
                        .setBucketType(uploadResponse.getBucketType())
                        .setTransientId(uf.getTransientId())
                        // Sha1 is used as ID in Neo
                        .setSha1(uploadResponse.getContentSha1())
                        .setActual(uploadResponse.getDownloadUrl())

                        .setB2Complete(true)
                );
            }

        }
    }
}
