@echo off
setlocal
chcp 65001 > nul

cd /d "%~dp0"

echo ==========================================================
echo   Avvio Automatico Motore di Ricerca Wikinews (HW4)
echo ==========================================================
echo.

set "DUMP_FILE=data\itwikinews.json.tar.gz"
set "INDEX_DIR=data\index"
set "JAR_FILE=target\wikinews-search.jar"

REM 1. Verifica presenza del dump
if not exist "%DUMP_FILE%" (
    echo [ERRORE] Il file del dump '%DUMP_FILE%' non e' presente!
    echo Scaricalo dal server Wikimedia e posizionalo in '%DUMP_FILE%'.
    pause
    exit /b 1
)

REM 2. Compilazione del JAR se mancante
if not exist "%JAR_FILE%" (
    echo [INFO] Compilazione del progetto tramite Maven Wrapper...
    call mvnw.cmd package -DskipTests
    if errorlevel 1 (
        echo [ERRORE] Compilazione fallita.
        pause
        exit /b 1
    )
    echo.
)

REM 3. Creazione dell'indice se non ancora presente
if not exist "%INDEX_DIR%" (
    echo [INFO] Indice non trovato. Avvio indicizzazione automatica di Wikinews...
    echo Richiede circa 30-50 secondi...
    java -jar "%JAR_FILE%" index "%DUMP_FILE%"
    if errorlevel 1 (
        echo [ERRORE] Indicizzazione fallita.
        pause
        exit /b 1
    )
    echo [OK] Indicizzazione completata con successo!
    echo.
) else (
    echo [OK] Indice Lucene presente in '%INDEX_DIR%'.
    echo.
)

REM 4. Avvio del server web e apertura browser
echo ==========================================================
echo   Avvio del Web Server su http://localhost:8000
echo ==========================================================
echo Il browser verra aperto automaticamente tra pochi istanti.
echo Premi CTRL+C in questa finestra per arrestare il server.
echo.

start "" "http://localhost:8000"
java -jar "%JAR_FILE%" serve

endlocal
