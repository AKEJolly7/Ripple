@echo off
rem 观澜（Ripple）Windows 一键运行
rem 需 JDK 17 与 Maven 在 PATH 中；JAVA_HOME 未指向 17 时请先 set JAVA_HOME
mvn -q clean package || goto :err
java -jar target\ripple-0.1.0.jar run-all || goto :err
echo.
echo 完成：output\nvda-events.html + artifacts\ 三件套
goto :eof

:err
echo 运行失败，请检查 JDK 17（java -version）与网络
exit /b 1
