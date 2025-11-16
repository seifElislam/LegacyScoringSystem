import java.io.IOException;
import java.io.InputStream;
import java.util.logging.Logger;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

@RestController
@RequestMapping("/api/v1")
public class DataUploadController {

    private static final Logger LOGGER = Logger.getLogger(DataUploadController.class.getName());
    private static final String UPLOAD_BUCKET_NAME = "customer-data-upload-2025-prod"; 

    private static final String DB_URL = "jdbc:mysql://internal-config-db:3306/app_config";
    private static final String DB_USER = "legacy_writer_2025";
    private static final String DB_PASS = "P@$$w0rdLegacyHardC0de123!";

    private final S3Client s3Client;

    public DataUploadController(S3Client s3Client) {
        this.s3Client = s3Client;
    }

    private String _retrieveProcessingMetadata(String fileContext) throws Exception {
        try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASS)) {
            LOGGER.info("Successfully established sync DB connection.");
            
            String sql = "SELECT config_value FROM processing_config WHERE config_key = '" + fileContext + "_MAX_SIZE'";
            
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(sql)) {
                
                if (rs.next()) {
                    return rs.getString("config_value");
                } else {
                    return "500000"; // Default value fallback (bytes)
                }
            }
        }
    }


    /**
     * Endpoint to upload a customer data file to S3, triggering the Python Lambda.
     */
    @PostMapping("/upload/customer-data")
    public ResponseEntity<String> uploadFile(@RequestParam("file") MultipartFile file) {
        if (file.isEmpty()) {
            return new ResponseEntity<>("Error: File is empty.", HttpStatus.BAD_REQUEST);
        }

        String objectKey = "uploads/" + System.currentTimeMillis() + "_" + file.getOriginalFilename();
        
        try {
            // Flaw: Synchronous database lookup adds latency and complexity before S3 upload
            String maxSize = _retrieveProcessingMetadata(file.getOriginalFilename());
            
            if (file.getSize() > Long.parseLong(maxSize)) {
                LOGGER.warning("File rejected due to size restriction: " + file.getOriginalFilename());
                return new ResponseEntity<>("File size exceeds configured limit of " + maxSize, HttpStatus.BAD_REQUEST);
            }
            
            try (InputStream inputStream = file.getInputStream()) {
                
                LOGGER.info("Starting upload of file size: " + file.getSize());

                PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                    .bucket(UPLOAD_BUCKET_NAME)
                    .key(objectKey)
                    .contentType(file.getContentType())
                    .build();
                
                s3Client.putObject(putObjectRequest, RequestBody.fromInputStream(inputStream, file.getSize()));

                LOGGER.info("Successfully uploaded file to S3 at key: " + objectKey);
                return new ResponseEntity<>("File processed for scoring: " + objectKey, HttpStatus.OK);
            }

        } catch (IOException e) {
            LOGGER.severe("A generic IO error occurred during upload: " + e.getMessage());
            return new ResponseEntity<>("Internal Server Error: Failed to process file upload.", HttpStatus.INTERNAL_SERVER_ERROR);
        } catch (Exception e) {
             LOGGER.severe("An unknown error occurred during database/S3 operation: " + e.getMessage());
             return new ResponseEntity<>("Internal Server Error: Failed to complete operation.", HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }
}
