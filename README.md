# AutoEMC-GTNH

AutoEMC-GTNH 是经典等价交换模组 **[ProjectE](https://github.com/sinkillerj/ProjectE/tree/MC17)** 的 1.7.10 附属模组,专为 **[GT New Horizons](https://www.gtnewhorizons.com/)** 整合包自动补全缺失的 EMC 值:服务端启动后扫描工作台、熔炉与全部 GT 机器配方,按"最便宜可得配方"递归求值,把 GTNH 数万种未被 ProjectE 定价的物品(机器产物、合成品、GT 材料形态等)逐一定价并注册回 ProjectE,让转化桌与 EMC 体系覆盖整条科技线。

本模组**不修改 ProjectE 与 GT 的源码**——只读扫描配方与注册表,在 ProjectE 完成自身映射(serverStarting 的 map#1)之后于 `serverStarted` 阶段计算缺失值,经 ProjectE 的 `APICustomEMCMapper` 注册并复刻 `/projecte reloadEMC` 重建映射(map#2);**凡是 ProjectE / 玩家已定价的物品(锚点)一律不覆盖**。GT 为软依赖,未安装时自动退化为只补工作台 + 冶炼产物。

## Progress

- [ ] 兼容更多mod
- [x] 配方图求值:工作台 > 单方块机器 > 多方块;同类别选低电压等级(蒸汽 < ULV < LV …),再比单位成本;递归环失效不自我抬价
- [x] GT 材料形态质量定价:粉/锭/板/线/管/矿石/方块等按 `材料质量 × 72 × 形态系数` 直接定价(GTMoreEMC 规则),命中即定、不再展开机器配方
- [x] 电路板同级平均:任意电路板价格 = 同等级电路板总价 / 数量,同等级 oredict 成员统一为均值
- [x] 同材料多副本有价优先:同一 oredict 有多个 mod 副本时,只要存在有价成员就选有价者
- [x] 配方树对齐:装 NEI-RecipeTree 时 `/projecte_autoemc view` 按引擎选中的配方整树强制对齐展开

## 核心机制

- **只补缺失**:ProjectE 已定价(含玩家 `/projecte setemc`、custom_emc.json 手动设置)的物品是锚点,永不覆盖;每次只把 `>0` 且非锚点的值注册回去
- **增量缓存**:结果与对齐链落盘 `config/emc-values.json`,指纹(配方集合 + 配置 + 公式版本 `FORMULA_VERSION`,当前 27)一致时只补差量,秒级启动;改了求值规则必须 +1 公式版本强制全量重算一次,否则旧缓存会短路新规则
- **工具不计价、流体按 144L 计价**:工作台工具槽、GT 一次性工具(编程/配置电路、ggfab 模具铸模)不消耗、不计价也不展开;流体输入按"每 144L = 该流体价值"计入成本(未定价流体按免费 0,受 `unpricedIsZero` 约束;GT 材料流体 144L = 对应锭价)
- **份量折算**:小撮粉/小堆粉/粒/螺栓/螺丝等按 GT 自带份量比折算到基准形态(小堆粉=粉/4、小撮粉=粉/9、粒=锭/9、螺栓/螺丝=杆/2),不再展开其配方;**满粉不折算**,和锭一样走配方比价(粉是材料体系的基础形态,自己有化学反应釜等产出配方,只有完全没有配方时才兜底折算成锭价)。配套两条防环规则:①"拿自己更小份量形态当原料"的重组配方(4 小堆粉=1 粉、9 粒=1 锭)不参与比价 —— 它的成本恒等于目标物品本身,只会把价钉死;②基准形态还没定价时,折算形态返回"未知"且不缓存 0(0 在成本里等于免费,会让吃它的配方以 0 成本胜出);③折算值属于"派生值",求值期间一律现算、不落表,收尾时(所有补偿/镜像之后)由 `materializeDerived()` 按最终基准价统一落表 —— 否则会留下"按中途基数"算出的旧值(粉中途 12800 时算出的小堆粉 3200,粉最终 204800)
- **回收配方排除**:逆向粉碎/逆向冶炼/电弧炉回收类(RECYCLE 元数据或 `*_recycling` 分类)不参与定价,防环防低估
- **规则叶子定价**:质量定价的材料形态、同级平均的电路板是"规则叶子"——价格由规则直接定义,配方树里不再展开它们的生产链,展示来源标注 `mass72` / `同级电路板平均`
- **无尽工作台优先**:无尽类物品(Avaritia / avaritiaddons 命名空间)只要候选里有大工作台(Extreme Crafting Table)配方就优先选它 —— 压过中子素压缩机等机器路径和普通合成台路径(仍让位给"恒等式防线/无价输入"两条正确性规则)
- **材料等价形态族**:锭/热锭/粉/小堆粉/小撮粉/粒/杆/螺栓/螺丝 是同一材料按 GT 材料量比(ingot=ingotHot=dust=M、dustSmall=M/4、dustTiny=nugget=M/9、stick=M/2、bolt=screw=M/8)的等价形态。收尾时只保留族内**最便宜的独立产线单价**(矿石/化工/机器,不吃同材料形态),其余形态 = 单价 × 材料量比、**不再展开自己的配方**(树里当规则叶子,链被剪掉)——即"到锭/粉就不再往下展开"。同材料形态互推的候选(锭->粉、粉->锭、粉←4×小堆粉…)在存在独立产线时不参与比价,避免"谁先求值谁定值"
- **对齐配方树**:服务端按最近一次求值的 chosen/picks 递归构建对齐链、分片下发;客户端打开 NEI-RecipeTree 时整树按引擎选择展开,关闭 GUI 后自动结束对齐
- **运行形态**:单机集成服务器在 `serverStarted` 内同步完成(避免与客户端 EMC 同步包并发改表崩连接,首次全量重算多等几秒);专用服务器后台线程计算、tick 应用,不阻塞启动

## 游戏指令

仅玩家可用,服务端与单机皆可,Tab 补全可用:

- `/projecte_autoemc view [<namespace>:<name>[@<meta>]]` — 查看物品的 EMC 与来源,并打开对齐配方树
  - 不带物品参数 = 查看手持物品

## 参考

- [GTToolMapper](https://www.curseforge.com/minecraft/mc-mods/gttoolmapper)
- [GT_MoreEMC](https://github.com/GTQT/GT_MoreEMC)
- [ProjectE-Integration](https://github.com/TagnumElite/ProjectE-Integration)

## 依赖

- **Minecraft 1.7.10** / **Forge 10.13.4.1614**
- **[ProjectE 1.7.10](https://github.com/sinkillerj/ProjectE/tree/MC17)**
- **GregTech GTNH 2.8.4**
- **[NEI-RecipeTree](https://github.com/XSana/NEI-RecipeTree)(可选)**
- 构建环境:Java 8+(GTNH 约定构建脚本,支持 jabel 现代语法)

## 构建与开发

```bash
# 克隆仓库
git clone git@github.com:A-Team-PJnCeADc/AutoEMC-GTNH.git
cd AutoEMC-GTNH

# 首次构建
./gradlew setupDecompWorkspace

# 构建模组(含 spotless 格式检查)
./gradlew build

# 产物
build/libs/autoemcgtnh-*.jar
```

## 贡献

欢迎提交 Pull Request 或 Issue。在提交前请确保:

1. 构建没有错误(`./gradlew build` 通过,含 spotless 格式检查)
2. 改了求值规则必须同步 `FORMULA_VERSION` +1(见 `ValueStore`),并跑一次全量重算核对数值
3. 通用代码放 `emc/` 公共包;客户端专用代码放 `client/`(NEI/RecipeTree 的类型化引用只能出现在 client 包)

## 致谢

- 所有参与开发、测试、建议的人员

## License

<a href="https://github.com/A-Team-PJnCeADc/AutoEMC-GTNH">AutoEMC-GTNH</a> © 2026 by <a href="https://github.com/A-Team-PJnCeADc">A-Team-PJnCeADc</a> is licensed under <a href="https://creativecommons.org/licenses/by-nc-sa/4.0/">Creative Commons Attribution-NonCommercial-ShareAlike 4.0 International</a>
