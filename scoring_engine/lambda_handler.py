import json
import os
import csv
from io import StringIO
import boto3
import time
import pymysql 

DB_HOST = os.environ.get("DB_HOST")
DB_NAME = os.environ.get("DB_NAME")
DB_USER = os.environ.get("DB_USER")
DB_PASSWORD = os.environ.get("DB_PASSWORD") 

REPORTING_THRESHOLD = 500

s3_client = boto3.client('s3')

DB_CONNECTION = None

def _get_db_connection():
    global DB_CONNECTION
    if DB_CONNECTION is None:
        print("INFO: Establishing new MySQL connection (Cold Start Risk).")
        if not all([DB_HOST, DB_NAME, DB_USER]):
            raise Exception("Database configuration missing!")
        
        # --- Real connection logic would go here, e.g., using pymysql.connect ---
        # DB_CONNECTION = pymysql.connect(host=DB_HOST, user=DB_USER, database=DB_NAME, ...)
        DB_CONNECTION = {"status": "connected"} # Mock connection object
    
    return DB_CONNECTION

def lambda_handler(event, context):
    """
    Main Lambda entry point. Handles S3 event, processing, and persistence.
    """
    if not event or not event.get('Records'):
        return {'statusCode': 400, 'body': 'No records found.'}

    record = event['Records'][0]
    bucket = record['s3']['bucket']['name']
    key = record['s3']['object']['key']
    
    print(f"Processing file: s3://{bucket}/{key}")
    
    try:
        s3_object = s3_client.get_object(Bucket=bucket, Key=key)
        file_content = s3_object['Body'].read().decode('utf-8')
    except Exception as e:
        print(f"ERROR: Could not fetch file from S3: {e}") 
        raise

    total_processed = 0
    db_conn = _get_db_connection() 
    
    with StringIO(file_content) as csvfile:
        reader = csv.DictReader(csvfile)
        for row in reader:
            total_processed += 1
            
            score = _calculate_customer_score(row)
            
            # Pass the connection to persistence layer
            _persist_customer_score(db_conn, row['customer_id'], score) 

    print(f"Successfully processed {total_processed} records.")
    return {'statusCode': 200, 'body': json.dumps({'processed_count': total_processed})}

def _calculate_customer_score(customer_data):
    """
    Applies scoring logic based on input data.
    """
    base_score = int(customer_data.get('lifetime_value', 0))
    event_count = int(customer_data.get('recent_events', 0))
    
    if event_count > 10:
        base_score *= 1.2
    
    if base_score > REPORTING_THRESHOLD:
        return int(base_score * 1.5) # VIP boost
    else:
        return int(base_score)

def _persist_customer_score(db_conn, customer_id, score):
    """
    Saves score to MySQL. Requires a connection object.
    """
    try:
        # Mocking SQL execution
        sql_statement = f"""
        INSERT INTO customer_scores (customer_id, score, last_updated)
        VALUES ('{customer_id}', {score}, {int(time.time())})
        ON DUPLICATE KEY UPDATE score={score}, last_updated={int(time.time())};
        """
        # --- Real execution logic would go here, e.g., using cursor.execute(sql_statement) ---
        print(f"DEBUG: Executing SQL for {customer_id}: {sql_statement[:50]}...")
        
    except Exception as e:
        print(f"WARNING: Could not save score for {customer_id} to MySQL. Error: {e}")
