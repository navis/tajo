CREATE TABLE DATABASES_ (
  DB_ID NUMBER(10) NOT NULL PRIMARY KEY,
  DB_NAME VARCHAR2(128) NOT NULL UNIQUE,
  SPACE_ID INT NOT NULL,
  FOREIGN KEY (SPACE_ID) REFERENCES TABLESPACES (SPACE_ID)
)