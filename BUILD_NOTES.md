# FirePlex3.0 build notes

## Main fix included
The GitHub Actions workflow now installs a known Gradle version before running the build.
The original project did not include `gradlew`, so calling `gradle assembleDebug` could fail if the runner did not already have Gradle installed.

## GitHub build
1. Upload/commit these files to GitHub.
2. Open the **Actions** tab.
3. Select **Build APK**.
4. Click **Run workflow**.
5. Download the **FirePlex-debug-apk** artifact when the build finishes.

## If the APK builds but app/login still fails
Check the response from:

```powershell
$headers = @{
    "Content-Type" = "application/json"
    "X-API-Key" = "YOUR_API_KEY"
}

$body = @{ username = "testuser" } | ConvertTo-Json

Invoke-RestMethod -Uri "https://plexpin.duckdns.org/api/app/login" -Method POST -Headers $headers -Body $body
```

## Security warning
The API key is currently hard-coded inside `AppAuthRepository.kt`. Anyone who gets the APK can extract it. For a public app, move this check server-side or issue per-device short-lived tokens.
