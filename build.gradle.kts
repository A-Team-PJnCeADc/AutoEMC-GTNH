plugins {
    id("com.gtnewhorizons.gtnhconvention")
}

repositories {
    // 依赖优先从 GTNH nexus public(聚合 Maven Central + GTNH releases/snapshots)下载,
    // 避免依赖逐个直连 repo.maven.apache.org / forge / sponge 造成慢速与超时。
    maven {
        name = "GTNH Nexus Public"
        url = uri("https://nexus.gtnewhorizons.com/repository/public/")
    }
}

gradle.projectsEvaluated {
    // gtnhgradle 在 apply/评估期注册默认仓库(Maven Central 等在 nexus 之前),且 repositories.gradle
    // 的仓库可能追加得更晚。projectsEvaluated 时全部已就位 —— 把 nexus public 提到依赖仓库容器
    // 首位,并移除 gtnhgradle 注册的同 URL 受限条目(功能已被无限制版覆盖),使依赖解析优先命中
    // 聚合仓库、少直连 Central。
    val repos = repositories as MutableList<org.gradle.api.artifacts.repositories.ArtifactRepository>
    repos.removeAll { it.name == "GTNH Maven" }
    val nexus = repos.find { it.name == "GTNH Nexus Public" }
    if (nexus != null) {
        repos.remove(nexus)
        repos.add(0, nexus)
    }
}

tasks.jar {
    manifest {
        // gtnhgradle 在 usesMixins=true 时会自动注入 MixinConfigs: mixins.autoemcgtnh.json,
        // 这会让 mixin 配置在 bootstrap 期(ProjectE 尚未进 classpath)提前处理,导致
        // launchwrapper 把 moze_intel.projecte.emc.EMCMapper 记为无法加载(负缓存),
        // 运行期 ProjectE map() 即 NoClassDefFoundError 崩溃。mixin 配置改由 LateMixinLoader
        // 在 FML CONSTRUCTING 阶段延迟注册,故此处移除该属性(参考 ProjectE-Team 同款配置)。
        attributes.remove("MixinConfigs")
    }
}
