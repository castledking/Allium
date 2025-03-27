# PowerShell script for building and deploying SFCore plugin
param (
    [string]$ProjectDir = $PSScriptRoot,
    [string]$ServerDir = "D:\Minecraft Servers\1.21.4",
    [string]$JavaHome = "C:\Program Files\Java\jdk-21"
)

function Build-And-Deploy {
    # Set environment variables
    $env:JAVA_HOME = $JavaHome
    $env:PATH = "$env:JAVA_HOME\bin;$env:PATH"

    Write-Host "Building SFCore plugin..."

    try {
        # Run Maven build
        Write-Host "Running Maven build..."
        Push-Location $ProjectDir
        $mavenOutput = & mvn clean install -U 2>&1 | Out-String

        # Check build result
        if ($LASTEXITCODE -ne 0) {
            Write-Host "Build failed! Error output:" -ForegroundColor Red
            $mavenOutput -split "`n" | Where-Object { 
                $_ -match "error" -and $_ -notmatch "DEBUG|INFO|WARNING" 
            } | ForEach-Object { Write-Host $_ -ForegroundColor Red }
            throw "Maven build failed with exit code $LASTEXITCODE"
        }

        # Copy plugin to server
        Write-Host "Copying plugin to server..."
        $pluginPath = Join-Path $ProjectDir "target\SFCore-1.0.jar"
        $destPath = Join-Path $ServerDir "plugins\SFCore.jar"
        
        if (-not (Test-Path $pluginPath)) {
            throw "Built plugin not found at $pluginPath"
        }
        
        Copy-Item $pluginPath $destPath -Force
        Write-Host "Build and deployment successful!" -ForegroundColor Green
        Write-Host "Plugin copied to: $destPath"

        # Start server
        Start-Server
    }
    catch {
        Write-Host "ERROR: $_" -ForegroundColor Red
        Read-Host "Press Enter to exit"
        exit 1
    }
    finally {
        Pop-Location
    }
}

function Start-Server {
    Write-Host "Starting Minecraft server..."
    $serverJar = Join-Path $ServerDir "server.jar"
    
    if (-not (Test-Path $serverJar)) {
        throw "Server jar not found at $serverJar"
    }

    Push-Location $ServerDir
    try {
        # Start server process
        $serverProcess = Start-Process -FilePath "java" `
            -ArgumentList "-Xms8G -Xmx16G -jar server.jar nogui" `
            -NoNewWindow -PassThru
        
        # Wait for server to exit
        $serverProcess.WaitForExit()
        
        # Prompt for restart
        Show-RestartPrompt
    }
    finally {
        Pop-Location
    }
}

function Show-RestartPrompt {
    do {
        Write-Host "`nServer has stopped. What would you like to do?"
        Write-Host "1. Restart the server"
        Write-Host "2. Exit"
        $choice = Read-Host "Enter your choice (1 or 2)"

        switch ($choice) {
            "1" {
                Write-Host "Restarting server..."
                # Kill any remaining Java processes
                Get-Process java -ErrorAction SilentlyContinue | 
                    Where-Object { $_.MainWindowTitle -eq "Minecraft server" } | 
                    Stop-Process -Force
                Start-Sleep -Seconds 2
                Build-And-Deploy
                return
            }
            "2" {
                Write-Host "Exiting..."
                exit 0
            }
            default {
                Write-Host "Invalid choice. Please enter 1 or 2." -ForegroundColor Yellow
            }
        }
    } while ($true)
}

# Main execution
Build-And-Deploy