# Install Traccar on Ubuntu 20.04

## Commands

### Install unzip utility and MySQL server:
apt update && apt -y install unzip mysql-server

### Set database password and create a new database:
mysql -u root --execute="ALTER USER 'root'@'localhost' IDENTIFIED WITH mysql_native_password BY 'root'; GRANT ALL ON *.* TO 'root'@'localhost' WITH GRANT OPTION; FLUSH PRIVILEGES; CREATE DATABASE tracktr;"

### Download the latest installer:
wget https://www.hypersolutionsph.com/download/tracktr-linux-64-latest.zip

### Unzip the file and run the installer:
unzip tracktr-linux-*.zip && ./tracktr.run

### Update the configuration file to use MySQL database:
nano /opt/tracktr/conf/tracktr.xml

<?xml version='1.0' encoding='UTF-8'?>

<!DOCTYPE properties SYSTEM 'http://java.sun.com/dtd/properties.dtd'>

    <entry key="config.default">./conf/default.xml</entry>

    <entry key='database.driver'>com.mysql.jdbc.Driver</entry>
    <entry key='database.url'>jdbc:mysql://localhost/tracktr?serverTimezone=UTC&amp;useSSL=false&amp;allowMultiQueries=true&amp;autoReconnect=true&amp;useUnicode=yes&amp;characterEncoding=UTF-8&amp;sessionVariables=sql_mode=''</entry>
    <entry key='database.user'>root</entry>
    <entry key='database.password'>root</entry>

### Start tracktr service:
service tracktr start