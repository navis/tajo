CREATE TABLE sales2 ( col1 int, col2 int)
PARTITION BY COLUMN (col3 int, col4 float, col5 text) using TEXTFILE;


