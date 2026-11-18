# Script PowerShell di avvio automatico per HW4
$ErrorActionPreference = "Stop"

Write-Host "==========================================================" -ForegroundColor Cyan
Write-Host "  Avvio Automatico Motore di Ricerca Wikinews (HW4)" -ForegroundColor Cyan
Write-Host "==========================================================" -ForegroundColor Cyan
Write-Host ""

$DumpFile = "data/itwikinews.json.tar.gz"
$IndexDir = "data/index"
$JarFile  = "target/wikinews-search.jar"

# 1. Verifica dump
if (-not (Test-Path $DumpFile)) {
    Write-Host "[ERRORE] File $DumpFile mancante!" -ForegroundColor Red
    Write-Host "Scaricare il dump da Wikimedia Enterprise e posizionarlo in $DumpFile."
    exit 1
}

# 2. Compilazione
if (-not (Test-Path $JarFile)) {
    Write-Host "[INFO] Compilazione JAR in corso..." -ForegroundColor Yellow
    & .\mvnw.cmd package -DskipTests
}

# 3. Indicizzazione se indice non presente
if (-not (Test-Path $IndexDir)) {
    Write-Host "[INFO] Indice non trovato. Indicizzazione del dump in corso..." -ForegroundColor Yellow
    & java -jar $JarFile index $DumpFile
} else {
    Write-Host "[OK] Indice Lucene presente in $IndexDir." -ForegroundColor Green
}

# 4. Avvio server
Write-Host ""
Write-Host "[OK] Avvio Web Server su http://localhost:8000..." -ForegroundColor Green
Start-Process "http://localhost:8000"
& java -jar $JarFile serve
