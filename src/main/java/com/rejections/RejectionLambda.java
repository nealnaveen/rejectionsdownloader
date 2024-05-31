package com.rejections;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;
import software.amazon.awssdk.services.rdsdata.RdsDataClient;
import software.amazon.awssdk.services.rdsdata.model.ExecuteStatementRequest;
import software.amazon.awssdk.services.rdsdata.model.ExecuteStatementResponse;
import software.amazon.awssdk.services.rdsdata.model.Field;
import software.amazon.awssdk.services.rdsdata.model.SqlParameter;
import software.amazon.awssdk.services.rdsdata.model.TypeHint;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

import java.sql.Timestamp;
import java.text.SimpleDateFormat;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;


public class RejectionLambda {

    private static final String DB_CLUSTER_ARN = "arn:aws:rds:us-east-1:508582898882:cluster:datacollector";
    private static final String DB_CREDENTIALS_ARN = "arn:aws:secretsmanager:us-east-1:508582898882:secret:rds!cluster-ce583560-543c-4698-81c0-e3a91caf27f1-S0lGc5";
    private static final String DB_NAME = "postgres";
    private static final Log log = LogFactory.getLog(RejectionLambda.class);
     public static void main( String args[]) {

         ObjectMapper mapper = new ObjectMapper();
         HttpClient client = HttpClient.newHttpClient();
         RdsDataClient rdsDataClient = RdsDataClient.builder()
                 .region(Region.US_EAST_1)
                 .build();

         int startNumber = 0;
         int totalQuantity = 0;
         boolean recordsAvailable = true;
         Region region = Region.US_EAST_1;
         DynamoDbClient ddb = DynamoDbClient.builder()
                 .region(region)
                 .build();


        Map<String, AttributeValue> item = getDynamoDBItem(ddb, "RejectionLambdaConfig", "ConfigName", "RejectionLambdaConfig");
        if (item != null && item.containsKey("StartNumber") && item.containsKey("TotalQuantity")) {
            startNumber = Integer.parseInt(item.get("StartNumber").n());
            totalQuantity = Integer.parseInt(item.get("TotalQuantity").n());
        }
        else{
            throw new RuntimeException("No DDB entry");
        }
        System.out.print("startNumber = " + startNumber);
        try   {
            while (recordsAvailable) {
                // Read startNumber and totalQuantity from DynamoDB

                String apiUrl = "https://developer.uspto.gov/ds-api/oa_rejections/v2/records";
                // Prepare the URL-encoded form data
                String formData = "criteria=" + URLEncoder.encode("*:*", StandardCharsets.UTF_8.toString()) +
                        "&start=" + URLEncoder.encode(String.valueOf(startNumber), StandardCharsets.UTF_8.toString()) +
                        "&rows=" + URLEncoder.encode(String.valueOf(totalQuantity), StandardCharsets.UTF_8.toString());

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(apiUrl))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .header("Accept", "application/json")
                        .header("Accept-Encoding", "gzip, deflate")
                        .POST(HttpRequest.BodyPublishers.ofString(formData))
                        .build();

                // Send the POST request
                HttpResponse response = client.send(request, gzipBodyHandler());
                System.out.println("Response status code: " + response.statusCode());

                //String responseString = new BasicResponseHandler().handleResponse(response);
                //System.out.println(response.body());
                // Parse JSON from the response body
                String content = response.body().toString();

                JsonNode rootNode = mapper.readTree(content);
                JsonNode results = rootNode.path("response").path("docs");

                if (results.isEmpty()) {
                    recordsAvailable = false;
                } else {
                    String sql = "INSERT INTO rejections (id, patentApplicationNumber, obsoleteDocumentIdentifier, groupArtUnitNumber, legacyDocumentCodeIdentifier, submissionDate, nationalClass, " +
                            "nationalSubclass, headerMissing, formParagraphMissing, rejectFormMissmatch, closingMissing, hasRej101, hasRejDP, hasRej102, hasRej103, hasRej112, hasObjection, cite102GT1, " +
                            "cite103GT3, cite103EQ1, cite103Max, signatureType, actionTypeCategory, legalSectionCode) VALUES (" +
                            ":id, :patentApplicationNumber, :obsoleteDocumentIdentifier, :groupArtUnitNumber, :legacyDocumentCodeIdentifier, :submissionDate, :nationalClass, :nationalSubclass, " +
                            ":headerMissing, :formParagraphMissing, :rejectFormMissmatch, :closingMissing, :hasRej101, :hasRejDP, :hasRej102, :hasRej103, :hasRej112, :hasObjection, :cite102GT1, " +
                            ":cite103GT3, :cite103EQ1, :cite103Max, :signatureType, :actionTypeCategory, :legalSectionCode)";

                    for (JsonNode node : results) {
                        java.sql.Date submissionDate = node.path("submissionDate").asText() != null &&
                                !node.path("submissionDate").asText().isEmpty() ?
                                getSQlDate(node.path("submissionDate").asText()) : null;
                        Map<String, SqlParameter> params = new HashMap<>();
                        params.put("id", param("id", node.path("id").asText()));
                        params.put("patentApplicationNumber", param("patentApplicationNumber", node.path("patentApplicationNumber").asText()));
                        params.put("obsoleteDocumentIdentifier", param("obsoleteDocumentIdentifier", node.path("obsoleteDocumentIdentifier").asText()));
                        params.put("groupArtUnitNumber", SqlParameter.builder().name("groupArtUnitNumber").value(Field.builder().longValue(node.path("groupArtUnitNumber").asLong()).build()).build());
                        params.put("legacyDocumentCodeIdentifier", param("legacyDocumentCodeIdentifier", node.path("legacyDocumentCodeIdentifier").asText()));
                        params.put("submissionDate", param("decisionDate",String.valueOf(submissionDate), TypeHint.DATE));
                        params.put("nationalClass", SqlParameter.builder().name("nationalClass").value(Field.builder().longValue(node.path("nationalClass").asLong()).build()).build());
                        params.put("nationalSubclass", param("nationalSubclass", node.path("nationalSubclass").asText()));
                        params.put("headerMissing", param("headerMissing", node.path("headerMissing").asBoolean()));
                        params.put("formParagraphMissing", param("formParagraphMissing", node.path("formParagraphMissing").asBoolean()));
                        params.put("rejectFormMissmatch", param("rejectFormMissmatch", node.path("rejectFormMissmatch").asBoolean()));
                        params.put("closingMissing", param("closingMissing", node.path("closingMissing").asBoolean()));
                        params.put("hasRej101", param("hasRej101", node.path("hasRej101").asBoolean()));
                        params.put("hasRejDP", param("hasRejDP", node.path("hasRejDP").asBoolean()));
                        params.put("hasRej102", param("hasRej102", node.path("hasRej102").asBoolean()));
                        params.put("hasRej103", param("hasRej103", node.path("hasRej103").asBoolean()));
                        params.put("hasRej112", param("hasRej112", node.path("hasRej112").asBoolean()));
                        params.put("hasObjection", param("hasObjection", node.path("hasObjection").asBoolean()));
                        params.put("cite102GT1", param("cite102GT1", node.path("cite102GT1").asBoolean()));
                        params.put("cite103GT3", param("cite103GT3", node.path("cite103GT3").asBoolean()));
                        params.put("cite103EQ1", param("cite103EQ1", node.path("cite103EQ1").asBoolean()));
                        params.put("cite103Max", SqlParameter.builder().name("cite103Max").value(Field.builder().longValue(node.path("cite103Max").asLong()).build()).build());
                        params.put("signatureType", SqlParameter.builder().name("signatureType").value(Field.builder().longValue(node.path("signatureType").asLong()).build()).build());
                        params.put("actionTypeCategory", param("actionTypeCategory", node.path("actionTypeCategory").asText()));
                        params.put("legalSectionCode", param("legalSectionCode", node.path("legalSectionCode").asText()));

                        ExecuteStatementRequest executeStatementRequest = ExecuteStatementRequest.builder()
                                .secretArn(DB_CREDENTIALS_ARN)
                                .resourceArn(DB_CLUSTER_ARN)
                                .database(DB_NAME)
                                .sql(sql)
                                .parameters(params.values())
                                .build();
                        ExecuteStatementResponse executeStatementResponse = rdsDataClient.executeStatement(executeStatementRequest);

                        System.out.println("Rows affected: " + executeStatementResponse.numberOfRecordsUpdated());
                    }
                    startNumber += totalQuantity;
                    System.out.print("Records inserted: " + totalQuantity);
                }

                // Update startNumber in DynamoDB
                UpdateItemRequest updateItemRequest = getUpdateItemRequest(startNumber);

                ddb.updateItem(updateItemRequest);

                System.out.println("DynamoDB updated "+startNumber);
            }

            // Update startNumber in DynamoDB

        } catch (Exception e) {
            e.printStackTrace();
            throw new RuntimeException(e);
        } finally {
            ddb.close();
        }
     }

    static SqlParameter param(String name, String value, TypeHint typeHint) {

        return SqlParameter.builder().typeHint(typeHint).name(name).value(Field.builder().stringValue(value).build()).build();

    }
    private static SqlParameter param(String name, boolean value) {
        return SqlParameter.builder().name(name).value(Field.builder().booleanValue(value).build()).build();
    }

    static SqlParameter param(String name, String value) {

        return SqlParameter.builder().name(name).value(Field.builder().stringValue(value).build()).build();
    }

    private static UpdateItemRequest getUpdateItemRequest(int startNumber) {
        Map<String, AttributeValueUpdate> updates = new HashMap<>();
        updates.put("StartNumber", AttributeValueUpdate.builder().value(AttributeValue.builder().n(Integer.toString(startNumber)).build()).build());
        Map<String, AttributeValue> key = new HashMap<>();
        key.put("ConfigName", AttributeValue.builder().s("RejectionLambdaConfig").build());

        UpdateItemRequest updateItemRequest = UpdateItemRequest.builder()
                .tableName("RejectionLambdaConfig")
                .key(key)
                .attributeUpdates(updates)
                .build();
        return updateItemRequest;
    }


    private static java.sql.Date getSQlDate (String date){
        java.sql.Date sqlDate = null;
        try {
            // Define the format of the input date string
            SimpleDateFormat sdf = new SimpleDateFormat("MM-dd-yyyy");
            // Parse the string into java.util.Date
            java.util.Date utilDate = sdf.parse(date);
            // Convert java.util.Date to java.sql.Date
            sqlDate = new java.sql.Date(utilDate.getTime());

            // Output the converted date
            System.out.println("Converted java.sql.Date: " + sqlDate);

        } catch (Exception e) {
            e.printStackTrace();
        }
        return sqlDate;
    }

    private static  Timestamp getSQLTimestamp(String timestampStr) {
        DateTimeFormatter formatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME;
        LocalDateTime dateTime = LocalDateTime.parse(timestampStr, formatter);
        return Timestamp.valueOf(dateTime);
    }

    public static Map<String, AttributeValue>  getDynamoDBItem(DynamoDbClient ddb, String tableName, String key, String keyVal) {
        HashMap<String, AttributeValue> keyToGet = new HashMap<>();
        Map<String, AttributeValue> returnedItem = null;
        keyToGet.put(key, AttributeValue.builder()
                .s(keyVal)
                .build());

        GetItemRequest request = GetItemRequest.builder()
                .key(keyToGet)
                .tableName(tableName)
                .build();

        try {
            // If there is no matching item, GetItem does not return any data.
             returnedItem = ddb.getItem(request).item();
            if (returnedItem.isEmpty())
                System.out.format("No item found with the key %s!\n", key);
            else {
                Set<String> keys = returnedItem.keySet();
                System.out.println("Amazon DynamoDB table attributes: \n");
                for (String key1 : keys) {
                    System.out.format("%s: %s\n", key1, returnedItem.get(key1).toString());
                }
            }

        } catch (DynamoDbException e) {
            System.err.println(e.getMessage());
            System.exit(1);
        }
        return returnedItem;
    }

    public static HttpResponse.BodyHandler<String> gzipBodyHandler() {
        return responseInfo -> {
            HttpResponse.BodySubscriber<InputStream> original = HttpResponse.BodySubscribers.ofInputStream();
            return HttpResponse.BodySubscribers.mapping(
                    original,
                    inputStream -> {
                        if ("gzip".equalsIgnoreCase(responseInfo.headers().firstValue("Content-Encoding").orElse(""))) {
                            try (GZIPInputStream gis = new GZIPInputStream(inputStream)) {
                                return new String(gis.readAllBytes(), StandardCharsets.UTF_8);
                            } catch (IOException e) {
                                throw new RuntimeException(e);
                            }
                        } else {
                            try {
                                return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
                            } catch (IOException e) {
                                throw new RuntimeException(e);
                            }
                        }
                    }
            );
        };
    }
}

