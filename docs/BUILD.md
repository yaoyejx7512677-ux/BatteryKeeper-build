# 构建说明

## 环境

- Windows 10/11。
- JDK 17。
- Android SDK Platform 36。
- Android Build Tools 35.0.0 或更高。
- Gradle Wrapper 8.13（项目内提供）。
- 网络可访问 Google Maven、Maven Central 或项目已配置的阿里云镜像。

推荐使用 Android Studio 打开项目根目录，也可从 PowerShell 构建。

## 环境变量

```powershell
$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-17'
$env:ANDROID_HOME = 'C:\Android\sdk'
```

路径按本机实际安装位置修改。

## Debug、测试与静态检查

```powershell
.\gradlew.bat assembleDebug
.\gradlew.bat testDebugUnitTest
.\gradlew.bat lintRelease
```

## Release

源码包不含私钥。需要签名时：

1. 将原 `batterykeeper.keystore` 放到项目根目录，或修改配置中的路径。
2. 将 `keystore.properties.example` 复制为 `keystore.properties`。
3. 填写 `storeFile`、`storePassword`、`keyAlias`、`keyPassword`。
4. 执行：

```powershell
.\gradlew.bat assembleRelease
```

产物位于 `app/build/outputs/apk/release/app-release.apk`。缺少 `keystore.properties` 时可生成未签名 Release；可直接安装的已签名版本位于交付包 `release/`。

## 校验

```powershell
& "$env:ANDROID_HOME\build-tools\35.0.0\apksigner.bat" verify --verbose --print-certs app\build\outputs\apk\release\app-release.apk
& "$env:ANDROID_HOME\build-tools\35.0.0\zipalign.exe" -c -P 16 4 app\build\outputs\apk\release\app-release.apk
```

## 清理

```powershell
.\gradlew.bat clean
```

