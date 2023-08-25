# 简介
基于CameraX和ExoPlayer实现摄像头预览、抓拍、录像和视频播放等功能。

本组件全部采用Kotlin进行书写，使用Material Design UI组件，适配夜间模式。

本组件兼容Android 11的Scoped Storage。

[![](https://jitpack.io/v/CraftsmanHyj/dk-camerax.svg)](https://jitpack.io/#CraftsmanHyj/dk-camerax)

# 工程项目配置
## 添加JitPack仓库
在工程项目根目录下的`build.gradle`中添加以下内容：

```groovy
allprojects {
    repositories {
        ...
        maven { url 'https://jitpack.io' }
    }
}
```

## 添加依赖库

```groovy
dependencies {
    implementation 'com.github.CraftsmanHyj:dk-camerax:Tag'
}
```

## 添加FileProvider
本组件需要配置相应的FileProvider。

```xml
<manifest>
    <application>
        <provider
            android:name="androidx.core.content.FileProvider"
            android:authorities="${applicationId}.FILE_PROVIDER"
            android:exported="false"
            android:grantUriPermissions="true">
            <meta-data
                android:name="android.support.FILE_PROVIDER_PATHS"
                android:resource="@xml/file_paths" />
        </provider>
    </application>
</manifest>
```

添加`res/xml/file_paths.xml`文件，其内容如下。

```xml
<?xml version="1.0" encoding="utf-8"?>
<paths>
    <files-path
        name="file"
        path="." />
    <cache-path
        name="cache"
        path="." />
    <external-files-path
        name="external_file"
        path="." />
    <external-cache-path
        name="external_cache"
        path="." />
</paths>
```

## 设置主题样式
本组件使用Material Design系列的UI组件，并适配了夜间模式。

App项目`AndroidManifest.xml`中的`<application>`要使用Material Design主题。

```xml
<resources>
    <style name="AppTheme" parent="Theme.MaterialComponents.DayNight">
        ...
    </style>
    ...
</resources>
```

```xml
<manifest>
    <application android:theme="@style/AppTheme">
    ...
</manifest>
```

可以通过修改res/values/colors.xml和res/values-night/colors.xml中的以下配色来调整本组件的UI颜色。

```xml
<resources>
    <color name="colorPrimary">#1E88E5</color>
    <color name="colorOnPrimary">#FFFFFF</color>

    <color name="colorPrimaryDark">#1565C0</color>
    <color name="colorOnPrimaryDark">#FFFFFF</color>

    <color name="colorSurface">#FFFFFF</color>
    <color name="colorOnSurface">#000000</color>

    <color name="textColorSecondary">#616161</color>
</resources>
```

# 摄像头界面
## 申请应用权限
`CameraFragment.REQUIRED_PERMISSIONS_FOR_CAPTURE`定义了摄像头界面需要的应用权限，请自行利用官方API或第三方权限库申请应用权限。

## CameraFragment
创建CameraFragment。

```kotlin
private val cameraFragment: CameraFragment = CameraFragment.newInstance(
    "${applicationId}.FILE_PROVIDER", // 已定义的FileProvider的authorities值
    CameraDirection.Front.code // 摄像头方向
)
```

将CameraFragment添加到界面上。

```kotlin
supportFragmentManager.beginTransaction()
    .add(R.id.container_camera, cameraFragment)
    .commitNow()
```

CameraFragment所在的Activity要实现CameraFragmentListener接口。

```kotlin
// 处理摄像头的操作结果
override fun onOperationResult(result: CameraOpResult) {
    when (result) {
        is CameraOpResult.Success -> { // 操作成功
            when (result.op) {
                CameraOp.Image -> // result.uri为照片路径
                CameraOp.Video -> // result.uri为视频路径
            }
        }
        is CameraOpResult.Failure -> // 操作失败
    }
}

// 预览时的每帧图像
override fun analyseImage(proxy: ImageProxy) {
}
```

## 抓拍
调用CameraFragment#takePicture()进行抓拍，操作结果回调给CameraFragmentListener#onOperationResult方法。

```kotlin
cameraFragment.takePicture()
```

## 录像

调用CameraFragment#startRecording()开始录制，stopRecording()结束录制。

操作结果回调给CameraFragmentListener#onOperationResult方法。

```kotlin
// 开始录制
cameraFragment.startRecording()
// 结束录制
cameraFragment.stopRecording()
```

# 图片预览界面
## PreviewImageFragment
创建PreviewImageFragment。

```kotlin
private val previewImageFragment = PreviewImageFragment()
```

将PreviewImageFragment添加到界面上。

```kotlin
supportFragmentManager.beginTransaction()
    .add(R.id.container_preview_image, previewImageFragment)
    .commitNow()
```

## 图片预览
调用PreviewImageFragment#setImageURI()并传入图片Uri。

```kotlin
previewImageFragment.setImageURI(uri)
```

# 视频播放界面
## PreviewVideoFragment
创建PreviewVideoFragment。

```kotlin
private val previewVideoFragment = PreviewVideoFragment()
```

将PreviewVideoFragment添加到界面上。

```kotlin
supportFragmentManager.beginTransaction()
    .add(R.id.container_preview_video, previewVideoFragment)
    .commitNow()
```

## 视频播放
调用PreviewVideoFragment#setVideoURI()并传入图片Uri。

```kotlin
previewVideoFragment.setVideoURI(uri)
```

