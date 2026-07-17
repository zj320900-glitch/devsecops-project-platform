在启动本次DevSecOps CI/CD流水线之前，您需要知道：示例中所用的Java源码较为简单，而生产环境的代码复杂度通常远高于此。但这并不影响您构建一条完整的生产级流水线，因为在实际工作中，DevSecOps工程师无需深入关注源码实现或pom.xml的具体依赖，这些细节应由开发团队负责提供。
本次流水线的最终效果演示：http://3.17.13.85:31000/
# 完整的 DevSecOps CI/CD 流水线 | 多可用区 Amazon EKS + Jenkins + Trivy + SonarQube + ECR + GitHub

# **介绍**

![](./images/a2f65408-ed78-4bc8-bdfc-6fcd51d69e6b.png)

---

1. **代码推送**  
   开发人员将源代码（`src`）和 `pom.xml` 文件推送到私有仓库，随后 DevOps 人员开始工作。

2. **Trivy 文件系统扫描（SCA）**  
   - 使用 Trivy 进行软件成分分析（SCA）。  
   - 扫描内容：  
     - `pom.xml` 中定义的第三方依赖库是否存在已知漏洞（CVE）。  
     - 操作系统包。  
     - 源代码中是否包含可访问的机密信息（如 AWS 密钥等）。  
   - 注意：Trivy 不检查代码逻辑本身，只检测依赖库漏洞、配置错误和已知密钥模式。  
   - **失败条件**：若发现漏洞，流水线立即失败。

3. **构建与测试（含 SAST）**  
   - 使用 Maven 构建代码，同时通过 SonarQube 插件执行静态应用安全测试（SAST）。  
   - SAST 用于检查代码本身的漏洞。  
   - **失败条件**（质量门禁）：  
     - 代码存在漏洞或质量不达标；  
     - 代码覆盖率低于 80%。  
   - 若任一项未通过，流水线失败。

4. **ECR 登录、构建镜像并推送**  
   - 执行 Amazon ECR 登录。  
   - 使用 Docker 等工具构建容器镜像。  
   - 将镜像推送到 AWS ECR。

5. **基础镜像漏洞扫描**  
   - 扫描 Dockerfile 使用的基础镜像及其操作系统包。  
   - **失败条件**：发现高危或严重漏洞，流水线失败。

6. **部署到 Amazon EKS**  
   - 涉及 AWS 认证与授权，遵循最小权限原则。  
   - Jenkins 负责部署，具体步骤：  
     - 创建 Jenkins 控制器（Controller）。  
     - 创建 Jenkins 代理（Agent）。  
     - 通过 SSH 建立控制器与代理的连接。

7. **SonarQube 与测试相关配置**  
   - 配置 SonarQube 并连接外部 PostgreSQL 数据库。  
   - `pom.xml` 中已定义 JUnit 作为测试框架依赖。  
   - 使用 Maven Surefire 插件运行 JUnit 测试用例。  
   - 使用 JaCoCo 插件收集 Java 代码覆盖率，并将报告传递给 SonarQube（SonarQube 本身不执行覆盖率分析）。

**关键失败节点总结**  
流水线中三个决定是否失败的关键环节：  
1. Trivy 文件系统扫描（SCA）  
2. SonarQube 质量门禁扫描（SAST + 覆盖率）  
3. Trivy 镜像扫描（基础镜像及其操作系统包）  
> 注意：镜像扫描在构建完成后、推送到镜像仓库之前执行，确保镜像无漏洞。

---



本次实验中，我们将构建一个端到端的**生产级 DevSecOps 流水线**，该流水线集成了安全编码、漏洞扫描、容器镜像加固、产物管理和自动化 Kubernetes 部署。目标是展示实际企业如何结合以下技术实现**安全的 CI/CD ：**

- Jenkins
- SonarQube（SAST + 覆盖率）
- Trivy（SCA + 图像扫描）
- AWS ECR
- 亚马逊 EKS
- Kubernetes YAML 清单
- IAM + RBAC 用于最小权限部署

在本实验结束时，您将了解每个阶段如何共同构建一个强化的交付流水线——从提交时扫描代码到在拓扑感知型 Kubernetes 集群上部署已签名和验证的镜像。此流程反映了现代企业**实际生产环境**中的部署方式。



## 您需要提前了解AWS IAM

AWS中的**IAM**（Identity and Access Management，身份与访问管理）是一个**全球性、免费**的云服务，核心功能是**安全管理用户及其对 AWS 资源和服务的访问权限**。

简单理解：**IAM 解决“谁能访问什么”**。

## 核心概念（你需要知道的关键术语）

| 概念                    | 说明                                              |
| ----------------------- | ------------------------------------------------- |
| **用户（User）**        | 代表一个人或应用程序，有长期凭证（密码/访问密钥） |
| **组（Group）**         | 多个用户的集合，方便统一授权                      |
| **角色（Role）**        | 没有凭证的身份，供用户、服务或 AWS 账户临时“扮演” |
| **策略（Policy）**      | JSON 格式的权限文档，定义允许或拒绝哪些操作       |
| **根用户（Root User）** | 账户创建时的超级管理员，权限最大（建议日常禁用）  |

## IAM 典型工作方式

1. 创建用户/组/角色  
2. 编写或选用托管策略（如 `AmazonS3ReadOnlyAccess`）  
3. 将策略附加到用户/组/角色  
4. 用户通过控制台、CLI 或 SDK 调用 AWS 服务，IAM 验证权限  

## 常见使用场景

- **企业员工**：开发人员只能读写 S3，不能操作 EC2  
- **跨账户访问**：开发账户中的角色允许生产账户的用户临时访问  
- **EC2 访问 S3**：给 EC2 实例附加 IAM 角色，无需存储长期密钥  
- **联合身份（SSO）**：用公司 Active Directory 或 Google 登录 AWS  

## 重要原则（安全最佳实践）

- ✅ **最小权限原则**：只给完成工作所需的最低权限  
- ✅ **启用 MFA**（多因素认证）  
- ❌ **不日常使用根用户**  
- ✅ **定期审查访问权限**（IAM Access Analyzer 可以帮助）  

## 举例说明

> 你想让新员工“张三”只能查看 S3 的某个存储桶（`my-company-data`），不能删除、不能操作 EC2。

做法：  

1. 创建 IAM 用户 `zhang.san`  
2. 创建内联策略或托管策略，允许 `s3:GetObject`、`s3:ListBucket` 对 `my-company-data`  
3. 附加策略给用户  
4. 用户使用控制台或 CLI 访问 S3  

## IAM 的特点总结

- **全球统一**：不按区域隔离，在“全球”区域配置  
- **免费**：不额外收费  
- **细粒度**：可控制到某个 S3 对象或某个 API 操作  
- **支持临时凭证**（通过 AWS STS）  

如果你刚开始使用 AWS，建议首先创建一个**非根用户的 IAM 管理员用户**，然后一直使用该用户操作，把根用户凭证安全保管起来。



## 实验：在 EC2 上安装 Jenkins Controller

controller连接agent默认使用JNLP协议（JAVA网络启动协议）更改后可用ssh协议连接

这台虚拟机**仅用作控制器**。所有流水线工作负载都将在基于 SSH 的 Jenkins 代理上执行。

------



## **1. 创建 EC2 实例**

---

### 创建 EC2 新实例（以 Jenkins Controller 为例）

1. **进入 EC2 服务**  
   左侧导航栏选择“实例”，然后点击右上角的“启动新实例”。

2. **基本配置**  
   - 名称：输入 `jenkins-controller`  
   - 操作系统：选择 **Ubuntu 24.04 LTS**  
   - 实例类型：选择 **c7i-flex.large**

3. **创建密钥对**  
   - 密钥对名称：`用户名+区域`（例如 `zhoujie-aws-Ohio`）  
   - 算法：选择 **ED25519**  
   - 注意：浏览器会自动下载私钥文件。若使用 Xshell 连接，需要用此私钥进行验证登录。

4. **安全组与存储**  
   - 安全组：根据需求自行配置  
   - 存储：配置为 **20 GiB**

5. **启动实例**  
   点击“启动实例”按钮。

6. **连接实例**  
   - 点击实例列表中的“连接”按钮  
   - 选择 **SSH 客户端** 选项卡  
   - 复制该选项卡中提供的 SSH 示例命令，用于后续登录。

- **AMI：** Ubuntu 24.04 LTS

- **实例类型：** c7i-flex.large（2核4g）

	> 此实例类型符合 AWS 基于积分的免费套餐资格

- **根目录空间：** 20 GB 或以上

**安全组（jenkins-controller VM）：**

- 允许来自您IP地址的**SSH（22）连接。**
- 允许来自您 IP 地址的**HTTP (8080) 请求**（用于 Jenkins UI）

------



## **2. 连接到实例**

```shell
ssh -i <your-key>.pem ubuntu@<jenkins-controller-public-ip>
```

请确保您的私钥受到限制：

```shell
chmod 600 ~/.ssh/<your-private-key>
chown $(whoami):$(whoami) ~/.ssh/<your-private-key>
```

设置主机名：

```shell
sudo hostnamectl set-hostname jenkins-controller
exec bash
```

设置时区：

```bash
sudo timedatectl set-timezone Asia/Shanghai
timedatectl status
```



------

## **3. 安装 Java 21 (JDK，jenkins核心和插件都需要JDK工具)**

Jenkins LTS 与 Java 21 兼容性良好。

```bash
sudo apt update
sudo apt install -y openjdk-21-jdk
java -version
javac -version
```



------

## **4. 添加 Jenkins 仓库并安装**

参考资料：https://www.jenkins.io/doc/book/installing/linux/（您可能需要去官网更新一下下面的命令）

```bash
sudo wget -O /etc/apt/keyrings/jenkins-keyring.asc \
  https://pkg.jenkins.io/debian-stable/jenkins.io-2026.key
echo "deb [signed-by=/etc/apt/keyrings/jenkins-keyring.asc]" \
  https://pkg.jenkins.io/debian-stable binary/ | sudo tee \
  /etc/apt/sources.list.d/jenkins.list > /dev/null
sudo apt update
sudo apt install jenkins
```



------

### **设置 Jenkins JVM 时区（基于 systemd 的安装）**

我们设置了 JVM 时区，以确保 Jenkins UI 的时间戳与服务器和代理的本地时间一致。如果省略此设置，JVM 通常以 UTC 时间运行，导致仪表盘/控制台的时间戳不一致。



**交互式（可使用您喜欢的编辑器进行编辑）：**

```shell
sudo systemctl edit jenkins
# 在编辑器中添加以下行并保存：
[Service]
Environment="JAVA_OPTS=-Duser.timezone=Asia/Shanghai"
```



**非交互式（直接写入覆盖）：**

```bash
sudo mkdir -p /etc/systemd/system/jenkins.service.d
cat <<'EOF' | sudo tee /etc/systemd/system/jenkins.service.d/override.conf > /dev/null
[Service]
Environment="JAVA_OPTS=-Duser.timezone=Asia/Shanghai"
EOF
```



------

## **5. 启动并启用 Jenkins**

```bash
sudo systemctl daemon-reload
sudo systemctl enable jenkins
sudo systemctl start jenkins
sudo systemctl status jenkins
```



------

## **6. 访问 Jenkins 用户界面并解锁**

在浏览器中打开：

```bash
http://<jenkins-controller-public-ip>:8080
```

获取管理员密码：

```bash
sudo cat /var/lib/jenkins/secrets/initialAdminPassword
```

然后：

- 安装推荐的插件
- 创建管理员用户
- 登录到控制面板

------



## **实验：在 EC2 上配置基于 SSH 的 Jenkins 代理**



---

### 基于已有实例创建 Jenkins Agent

1. **进入 Jenkins Controller 实例的操作菜单**  
   选中已创建的 `jenkins-controller` 实例，依次点击：  
   **操作 → 映像和模板 → 启动更多类似实例**

2. **修改配置以创建 Agent 实例**  
   - **名称**：将实例名称改为 `jenkins-agent`  
   - **安全组**：根据需要更改安全组配置  
   - **存储**：调整配置存储（如大小或类型）

3. **启动实例**  
   完成上述修改后，点击“启动实例”。

---

### 补充说明：安全组中的 Name 与实例中的 Name 的区别

- **安全组里的 Name**：指的是 **标签（Tag）名**，默认情况下安全组没有此标签。  
- **实例列表中的 Name**：实际上是实例的 **标签（Tag）的键值**，即 `Name` 标签的值。

---

如果需要进一步细化存储或安全组的具体参数，可补充说明。

这台虚拟机将执行所有 Jenkins 作业。它将安装 JDK 21、Docker 和一个非 root`jenkins`用户。

------

### **1. 创建 EC2 实例**

- **AMI：** Ubuntu 24.04 LTS
- **实例类型：** c7i-flex.large
- **根目录空间：** 30 GB 以上（简单的扫描需要空间）

**安全组（jenkins-agent VM）：**

- 仅允许来自控制器 IP 的**SSH (22)连接**
- 不要在代理上暴露 Jenkins UI（8080 端口）。

> 注意：Jenkins 控制器通过 SSH（端口 22）连接到代理。仅当您使用 JNLP 入站代理时才需要在控制器上打开 TCP 50000 端口；否则无需打开。

------

### **2. 连接到实例**

```bash
ssh -i <your-key>.pem ubuntu@<jenkins-agent-public-ip>
```

保护好您的 SSH 密钥：

```bash
chmod 600 ~/.ssh/<your-private-key>
chown $(whoami):$(whoami) ~/.ssh/<your-private-key>
```

设置主机名：

```bash
sudo hostnamectl set-hostname jenkins-agent
exec bash
```

设置时区：

```bash
sudo timedatectl set-timezone Asia/Shanghai
timedatectl status
```



------

### **3. 安装 Java 21**

```bash
sudo apt update
sudo apt install -y openjdk-21-jdk
java -version
javac -version
```



------

### **4. 创建一个专用的 Jenkins 用户（非 root 用户）**

```
# 创建一个专用的非 root 'jenkins' 用户，并分配家目录和 bash shell
sudo useradd -m -s /bin/bash jenkins
```

------

## 实验：将 jenkins-agent 添加到 Jenkins 控制器

![](./images/e88da092-eb4e-47ff-832b-ff6db21ab837.png)

通过 SSH 连接到控制器：

```bash
ssh -i <your-key>.pem ubuntu@<jenkins-controller-public-ip>
```



------

### **1. 在控制器（Jenkins 用户）上生成 SSH 密钥**

Jenkins 服务以 身份运行`jenkins`，因此我们在其主目录中生成密钥。

请使用**有意义的文件名**和**不同的注释**：

```shell
# 你将进入 jenkins 用户的家目录，即 /var/lib/jenkins
sudo su - jenkins
mkdir .ssh
cd .ssh/
ssh-keygen -t ed25519 -f /var/lib/jenkins/.ssh/jenkins-agent-key -C "jenkins-agent-access"
```



解释：

- **文件：** `/var/lib/jenkins/.ssh/jenkins-agent-key`
- **评论：** `jenkins-agent-access`
- 为了清晰起见，它们特意有所不同。

显示公钥：

```
cat /var/lib/jenkins/.ssh/jenkins-agent-key.pub
```



------

### **2. 将公钥复制到代理。**

> **假设您已经与**控制器和代理建立了 SSH 会话。

#### **手动复制（推荐用于 EC2）**



1. **在控制器（例如 Jenkins）上，打印出公钥并复制它。**

```
sudo su - jenkins
cat ~/.ssh/jenkins-agent-key.pub
```



复制以“.”开头的单行`ssh-ed25519`。

1. **在代理上（SSH 会话已打开），切换到 jenkins 用户**

```bash
sudo su - jenkins
mkdir -p ~/.ssh
vim ~/.ssh/authorized_keys
#把上面在控制器（例如 Jenkins）上，打印出公钥并复制它的内容复制过来
```



> 这将提示您为该用户设置密码`jenkins`。Ubuntu 服务帐户通常创建时没有本地密码，因此交互式界面`sudo su - jenkins`可能会要求您创建一个密码以允许直接登录。注意：从 root 用户或通过其他方式切换用户`sudo`不需要目标用户的密码；要避免设置密码，请使用 root shell 或`sudo -i -u jenkins`其他方式。

将公钥粘贴到此处，保存并退出。

1. **设置权限（既然您已经是 Jenkins 用户，在agent上）**

```
chmod 700 ~/.ssh
chmod 600 ~/.ssh/authorized_keys
```



1. 从**控制器**测试连接

> 以**jenkins**用户身份运行此命令，因此`~`会展开为`/var/lib/jenkins`.

```shell
# 切换到 jenkins 用户（会加载 jenkins 的家目录环境）
sudo su - jenkins

# 使用 jenkins 家目录中的私钥测试 SSH 连接
ssh -i /var/lib/jenkins/.ssh/jenkins-agent-key jenkins@agent的IP hostname
ssh -i /var/lib/jenkins/.ssh/jenkins-agent-key jenkins@3.128.172.62 hostname
# 预期输出：
# jenkins-agent

# 完成后，退出 jenkins 的 shell 返回到之前的用户
exit
```



**为什么这很重要**：以管理员身份运行该命令`jenkins`可确保私钥路径解析到指定位置。以其他用户身份（例如）`/var/lib/jenkins/.ssh/jenkins-agent-key`运行相同的命令会查找并失败。`ssh -i ~/.ssh/jenkins-agent-key ...``ubuntu``/home/ubuntu/.ssh/jenkins-agent-key`

------

### **3. 在 Jenkins UI 中配置代理**

1. Jenkins 控制面板 →**管理 Jenkins → 云和节点 → 新建节点**

2. 姓名：`jenkins-agent`

3. 类型：**固定节点**

4. 配置：

	- **Number of executors：（** 并行执行任务的个数`1`或根据需要）
	- **远程工作目录：** `/home/jenkins` ← 确保这与代理的 Jenkins 主目录匹配，agent上的jenkins用户对这个目录有足够访问权限，所以这里不能够乱填写。
	- **标签：** `docker-maven-trivy`
	- **用法：**尽可能多地使用此节点
	- **启动方式：**通过**SSH启动代理。**

5. 请输入连接详细信息：

	- **主机：** `<jenkins-agent-PRIVATE-ip>`

	- **凭证：**添加 → **SSH 用户名及私钥**（SSH  Username  with  privatekey）

		ID：jenkins-agent

		- **Username：** `jenkins`
		- **Private Key（勾选Enter directly）：**`/var/lib/jenkins/.ssh/jenkins-agent-key`粘贴（控制器上的）私钥文件内容（注意有坑，一定要把-----BEGIN RSA PRIVATE KEY-----和-----END RSA PRIVATE KEY-----复制到凭证里否则会失败，连不上agent）。
		- **Passphrase：**除非您设置了密码短语，否则请留空。

6. 主机密钥验证策略（Host Key Verification Strategy）（选择一项）

	- **建议在生产环境中使用已知主机文件验证策略** 。Jenkins 使用其内部`known_hosts`文件。请预先填充该文件（`ssh`通过控制器一次性填充`ssh-keyscan`），以便自动信任连接。

	- **手动提供的密钥验证策略** 非常安全。您需要手动将代理的主机密钥指纹粘贴到 Jenkins 中。此方法适用于必须进行带外密钥验证的受监管环境。

	- **手动信任密钥验证策略** 适用于小型团队和受控实验室环境。Jenkins 会在首次连接时 (TOFU) 接受主机密钥并将其保存为受信任密钥。仅当您在首次连接时可以信任网络时才使用此策略。

	- **不进行验证的验证策略** 不安全。Jenkins 不会验证主机密钥。仅适用于安全性无需考虑的隔离测试环境。

		#### 我们选择`Known hosts file Verification Strategy`

		

		使用**已知主机文件验证策略**`known_hosts`。手动从控制器填充 Jenkins 的已知主机文件，以便自动信任未来的代理连接。以`jenkins`控制器用户身份运行此命令（`yes`首次连接时会提示并附加主机密钥）：

		```
		sudo -u jenkins ssh -i /var/lib/jenkins/.ssh/jenkins-agent-key jenkins@172.31.40.86 ls -la
		```

		

		另一种方法（非交互式）：无需交互式提示即可获取并附加代理主机密钥：

		```
		ssh-keyscan 172.31.40.86 | sudo -u jenkins tee -a /var/lib/jenkins/.ssh/known_hosts > /dev/null
		```

		

		完成任一步骤后，Jenkins 将使用其`known_hosts`文件进行安全、自动化的 SSH 验证。

7. 高级（可选）：

	- **SSH 端口：**如果代理 SSH 运行在非标准端口上（默认为 22），则更改此端口。
	- **连接超时/重试：**如果网络延迟较高，则增加此值。
	- **远程文件系统根目录注意事项：**如果代理用户的主目录与远程文件系统根目录不同`/home/jenkins`，请相应地更新远程文件系统根目录。
	- **工具位置/Java 路径：**仅当代理 Java 非标准时才设置。

8. 保存 → 点击**启动代理**

**预期结果：**

```
Agent successfully connected and online
```



**快速验证步骤（如果界面显示已连接，但您想确认）：**

- 在代理页面上，单击**“日志”**，然后查找`Authentication successful`“/ `Agent successfully connected`”行。
- 创建一个仅针对此节点（标签）的小型作业，并添加一个 shell 步骤：

```
echo "hello from agent"
hostname
whoami
```



控制台应打印代理主机名和`jenkins`。

------

## 实验：在 EC2 上安装 SonarQube

在上面创建的实例上点击操作->印象和模板->启动更多类似实例，更改名字为sonarqube，更改安全组，更改配置存储，点击启动实例，连接和上面的操作一样

在实验环境中，我们将 SonarQube 和 PostgreSQL 部署在**同一台虚拟机**上。这对于实验来说没问题，也足够接近小型生产环境的虚拟机部署。

### 1. 创建 EC2 实例



- AMI：Ubuntu 22.04 LTS

- 实例类型：c7i-flex.large（SonarQube 喜欢 RAM）

	> **注意：**实例类型**c7i-flex.large**符合 AWS**基于信用额度的免费套餐条件**，因此只要您的信用额度余额未超过上限，就不会产生费用。

- 根目录空间：20 GB 或以上

**安全组（SonarQube VM）**

- 允许来自您IP地址的SSH（22）连接。
- 允许来自您的 IP 地址和 Jenkins 虚拟机的 HTTP (9000) 请求。

### 2. 连接到实例



```bash
ssh -i <your-key>.pem ubuntu@<sonarqube-ec2-public-ip>
```



**注意：**请确保您的 SSH 私钥只有您自己可以读取。运行：

```bash
chmod 600 ~/.ssh/<your-prvate-key>  
chown $(whoami):$(whoami) ~/.ssh/<your-prvate-key>  
```



**建议主机名：** `sonarqube` 这样可以在 SSH 会话、监控仪表板和基于 DNS 的服务发现中轻松识别服务器。

```bash
# 设置主机名并重新加载你的 shell
sudo hostnamectl set-hostname sonarqube
exec bash
```



**设置正确的时区** 请设置机器时区，以便日志和时间戳与您所在地区保持一致。我将使用`Asia/Shanghai`；请选择与您所在位置相符的时区。

```bash
# 设置时区为亚洲/上海
sudo timedatectl set-timezone Asia/Shanghai

# 验证
timedatectl status
# 或
date
```



### 3. 安装 Java 21

SonarQube 目前需要 Java 21。

```
sudo apt-get update
sudo apt-get install -y openjdk-21-jre
java -version
```



你应该看看Java 21。

### 4. 安装 PostgreSQL 并为 SonarQube 创建数据库

在 SonarQube 中，**数据库至关重要**。它存储：

- 所有**项目、分析和衡量**（问题、覆盖范围、重复项、评级、技术债务）。
- **规则、质量配置文件、质量门**、用户帐户、令牌和常规配置。

由于这些数据非常重要，因此在生产环境中，您经常会看到企业在**单独的、自管理的 PostgreSQL VM**上运行 SonarQube 数据库，或者使用**Amazon RDS for PostgreSQL 等托管服务**，以便性能、备份和高可用性可以独立于 SonarQube 应用程序 VM 进行处理。

在**开发和小规模测试环境**中，有时会看到使用**H2。H2是一个****嵌入式、基于内存或文件的关系型数据库**，上手非常容易，但并不适合高负载、多用户的生产环境。它适用于快速的本地测试，但对于任何严肃的团队环境，SonarQube 建议使用**PostgreSQL**。

```shell
# 安装 PostgreSQL 及常用扩展
sudo apt install -y postgresql postgresql-contrib

# 设置 PostgreSQL 开机自动启动
sudo systemctl enable postgresql

# 立即启动 PostgreSQL 服务
sudo systemctl start postgresql
```



**创建 SonarQube 数据库和用户**

以下是创建数据库角色和`sonarqube`数据库的完整命令，以及快速连接测试的步骤。请以超级用户身份运行 SQL 命令`postgres`。

```bash
# 以 postgres 超级用户身份打开 PostgreSQL 命令行
sudo -u postgres psql
```

```shell
-- 为 SonarQube 创建一个带密码的登录角色

CREATE ROLE sonar WITH LOGIN ENCRYPTED PASSWORD 'sonar';

-- 创建由该角色拥有的 SonarQube 数据库
CREATE DATABASE sonarqube OWNER sonar;

-- 将数据库上的所有权限授予 sonar 角色
GRANT ALL PRIVILEGES ON DATABASE sonarqube TO sonar;

-- 退出 psql
\q
```



快速验证（在虚拟机 shell 中运行）：

```bash
# 列出所有角色以确认 'sonar' 角色存在
sudo -u postgres psql -c "\du"

# 列出所有数据库及其所有者以确认 'sonarqube' 数据库存在
sudo -u postgres psql -c "\l"

# 模拟 SonarQube 的连接方式进行测试（如需请替换密码）
PGPASSWORD='sonar' psql -U sonar -h localhost -d sonarqube -c "\dt"
#显示结果：Did not find any relations.
```



如果最后一条命令显示关系列表为空（没有表）且没有身份验证错误，则说明数据库和用户配置正确。请更新 `<database_name>`、`<user_name>`和`sonar.jdbc.username`` `sonar.jdbc.password`<user_name> `的值以匹配这些值，然后重启 SonarQube。`sonar.jdbc.url``/opt/sonarqube-current/conf/sonar.properties`

### 5. 调整 SonarQube 所需的内核设置



SonarQube 内部使用 Elasticsearch，需要对内核进行一些调整。

```bash
# 增加内存映射区域的最大数量（Elasticsearch 需要此设置）
echo 'vm.max_map_count=524288' | sudo tee -a /etc/sysctl.d/99-sonarqube.conf

# 增加最大文件句柄数
echo 'fs.file-max=131072' | sudo tee -a /etc/sysctl.d/99-sonarqube.conf

# 应用新的 sysctl 系统参数设置
sudo sysctl --system
```



`sonar`通过添加以下内容为用户设置 ulimits `/etc/security/limits.d/99-sonarqube.conf`：

```bash
# 允许 sonar 用户打开大量文件
echo 'sonar   -   nofile   131072' | sudo tee /etc/security/limits.d/99-sonarqube.conf

# 允许 sonar 用户运行大量进程
echo 'sonar   -   nproc    8192'   | sudo tee -a /etc/security/limits.d/99-sonarqube.conf
```



> **注意：**这些内核和 ulimit 设置调整可为 Elasticsearch（SonarQube 使用）提供足够的内存映射和文件描述符，以确保代码索引的可靠性。请在启动 SonarQube 之前应用这些设置，以避免启动失败、索引失败或资源限制问题。

### 6. 创建一个专门的Sonar用户



```
# 创建一个专用的 'sonar' 用户，其家目录位于 /opt/sonarqube
sudo useradd -m -d /opt/sonarqube -s /bin/bash sonar
```



### 7. 下载并安装 SonarQube



参考链接：https://www.sonarsource.com/products/sonarqube/downloads/

请从 SonarQube 网站查看 LTS 下载 URL；例如：

```shell
# 切换到临时文件夹进行下载（保持 /tmp 目录整洁，避免权限问题）
cd /tmp

# 下载 SonarQube 社区版压缩包，跟随重定向（-L），将输出保存为 sonarqube.zip（-o）
curl -L -o sonarqube.zip "https://binaries.sonarsource.com/Distribution/sonarqube/sonarqube-26.5.0.122743.zip"

# 非交互式安装 unzip 工具（-y 自动接受提示）
sudo apt-get update
sudo apt-get install -y unzip

# 解压已下载的压缩包到 /tmp 目录
unzip sonarqube.zip

# 将解压后的文件夹移动到稳定的部署位置
sudo mv sonarqube-26.5.0.122743 /opt/sonarqube-current

# 递归（-R）更改所有权，让 sonar 系统用户拥有所有文件和目录
sudo chown -R sonar:sonar /opt/sonarqube-current
```



升级时可以将其保留`/opt/sonarqube-current`为符号链接。

> 在企业级部署中，通常会使用**数据中心版 (DCE)** ，因为它提供应用级高可用性。DCE 支持集群**式**SonarQube：多个应用节点位于负载均衡器之后，配备专用 Elasticsearch 搜索节点，并共享外部数据库，从而实现弹性、可扩展性和零停机维护。

### 8. 配置 SonarQube 使用 PostgreSQL



我们正在配置 SonarQube 以连接到您创建的 PostgreSQL 数据库。PostgreSQL **的默认端口是`5432`**，除非您的数据库使用不同的端口，否则请在 JDBC URL 中使用该端口。

```shell
# 以 'sonar' 用户身份编辑 SonarQube 主配置文件
# （使用你偏好的编辑器：vim/nano）
sudo -u sonar vim /opt/sonarqube-current/conf/sonar.properties
```



取消注释并设置以下几行（如果需要，请替换密码/主机名）：

```shell
# SonarQube 的数据库用户名（你创建的那个数据库用户）
sonar.jdbc.username=sonar

# 该数据库用户的密码；请保密并确保与你的数据库设置一致
sonar.jdbc.password=sonar

# JDBC 连接字符串：jdbc:postgresql://<主机>:<端口>/<数据库名>
# PostgreSQL 默认端口为 5432 —— 由于数据库在同一台虚拟机上，故使用 localhost
sonar.jdbc.url=jdbc:postgresql://localhost:5432/sonarqube
```



备注和快速检查：

- 如果 PostgreSQL 运行在不同的主机上，请将 替换`localhost`为数据库主机或 IP 地址，并确保数据库接受远程连接（`listen_addresses`和`pg_hba.conf`）。
- 保存后，重启 SonarQube，使其应用新的数据库设置。
- 如果 SonarQube 进程无法连接，请检查 PostgreSQL 是否正在运行以及`sonar`用户是否具有数据库访问权限`sonarqube`。

### 9. 为 SonarQube 创建一个 systemd 服务



**systemd 单元文件是什么：**一个小型配置文件，它告诉操作系统如何启动、停止和管理服务，哪个用户运行它，它依赖于什么，重启策略和资源限制。

创建单元文件（以 root 用户身份编辑）：

```bash
sudo vim /etc/systemd/system/sonarqube.service
```



请粘贴以下单元文件（文件内不要包含任何注释）：

```bash
[Unit]
Description=SonarQube service
After=network.target postgresql.service

[Service]
Type=forking
User=sonar
Group=sonar
ExecStart=/opt/sonarqube-current/bin/linux-x86-64/sonar.sh start
ExecStop=/opt/sonarqube-current/bin/linux-x86-64/sonar.sh stop
Restart=on-failure
LimitNOFILE=131072
LimitNPROC=8192

[Install]
WantedBy=multi-user.target
```



重新加载 systemd 并启动服务：

```bash
# 重新加载 systemd 以应用新的单元
sudo systemctl daemon-reload

# 启用 SonarQube 开机启动
sudo systemctl enable sonarqube

# 现在启动 SonarQube服务
sudo systemctl start sonarqube

# check current status; it should become # 检查当前状态；它应该变为active (running)
sudo systemctl status sonarqube
```



**注意：单元文件指令说明（请勿将这些内容写入单元文件）**

- `Description`— 为该服务起一个易于理解的名称。
- `After`— 在列出的单元启动后启动此服务（此处指网络和 PostgreSQL）。
- `Type=forking`— 该服务在后台运行；systemd 将原始进程视为启动进程。
- `User`/ `Group`— 运行该服务的系统帐户（`sonar`） 以最小权限运行。
- `ExecStart`— 启动 SonarQube 的命令的完整路径。该命令必须是可执行文件。
- `ExecStop`— 能够干净利落地停止 SonarQube 的命令的完整路径。
- `Restart=on-failure`— 如果服务崩溃或因错误退出，则自动重新启动服务。
- `LimitNOFILE`— 提高打开文件数限制；Elasticsearch 需要很多文件描述符。
- `LimitNPROC`— 提高服务用户允许的进程数。
- `WantedBy=multi-user.target`— 使服务在系统正常启动时启动。

**操作提示：**

- 如果状态不是`active (running)`，请检查实时日志：`sudo journalctl -u sonarqube -f`。
- 确认`sonar`用户拥有该权限`/opt/sonarqube-current`，并且该`sonar`用户已事先设置了 ulimits。
- 编辑单元文件后，务必运行`sudo systemctl daemon-reload`以应用更改。

**故障排除：** 检查 SonarQube 日志（查找错误/引导失败）

```bash
# show recent sonar logs (general), web and elasticsearch logs
sudo tail -n 200 /opt/sonarqube-current/logs/sonar.log
sudo tail -n 200 /opt/sonarqube-current/logs/web.log
sudo tail -n 200 /opt/sonarqube-current/logs/es.log
```



### 10. 访问 SonarQube 用户界面

在您的浏览器中：

```
http://<sonarqube-ec2-public-ip>:9000
```



默认登录名：

- 用户名：`admin`
- 密码：`admin`

您将被要求更改密码。

从这里您可以：

- 探索**质量概况**、**质量关卡**和**规则**
- 稍后，创建一个**项目**和一个Jenkins将使用的**令牌。**
- `SONAR_HOST_URL`在 Jenkins 中配置如下`http://<sonarqube-ec2-public-ip>:9000`

### 11. SonarQube 的生产说明



在许多生产部署中：

- SonarQube 运行在**一个或多个虚拟机**上，而 PostgreSQL 通常运行在单独的数据库服务器或托管服务上。
- 资源规模根据项目和用户数量而定，通常比实验室环境需要更多的 CPU 和内存。
- 访问通过**HTTPS**进行，前端由 ALB 或 Nginx 提供服务。
- **身份验证与LDAP、SSO 或 OIDC**集成
- **数据库**和**配置**都计划进行备份。
- 有些团队将 SonarQube 迁移到**Kubernetes**，但像这样的虚拟机模式仍然非常普遍。

# 演示：端到端生产DevSecOps流水线



## 我们该怎么办？

![](./images/a2f65408-ed78-4bc8-bdfc-6fcd51d69e6b.png)

在本演示中，我们使用**Jenkins**、**Trivy**、**SonarQube**、**AWS ECR**和**Amazon EKS**构建了一个**生产级 DevSecOps 流水线**。该流水线通过**扫描源代码**、**运行单元测试**、**执行静态分析**、**扫描容器镜像等****多重安全机制**来确保安全，并且**只有在所有阶段都通过验证后才进行部署**。我们遵循**最小权限原则**，使用**不可变的镜像标签**，并在完成部署前**验证部署的健康状况。**



------

## **第一阶段：Git检出和Jenkins流水线作业设置**



*（参考：流水线图中的“检出私有 Git 仓库”框）*

### **客观的**



准备好源代码控制系统，并设置一个能够安全地检出私有仓库的 Jenkins 流水线作业。目前不需要任何构建工具——只需要**Git**（Ubuntu 24.04 上已经提供，并通过 Jenkins Git 插件使用）。

------

#### **0. 创建一个私有GitHub仓库**



创建 Jenkins 将克隆并推送代码的私有仓库。该仓库将存放我们的应用程序代码、`pom.xml`Kubernetes 清单文件、Dockerfile 和 Jenkinsfile，稍后也会存放这些文件。

- 前往**GitHub → 新建仓库**
- 命名为：**zhoujie-private-repo**
- 可见性：**私密**
- 这是 Jenkins 将从中拉取和推送构建相关更新的仓库。

------

#### **1. 创建 GitHub 个人访问令牌 (PAT)**



此令牌允许 Jenkins 对您的私有仓库进行身份验证。

- 前往**GitHub → 设置 → 开发者设置 → 个人访问令牌**
- 创建具有指定范围的**经典 PAT**：使用此选项是因为**推送**`repo`操作 将使用同一个令牌。（在生产环境中，您应该将范围限制为最小权限。）
- **复制令牌**——GitHub 将不会再次显示它。

------

#### **2. 将 GitHub PAT 添加到 Jenkins 凭据中**



路径：**管理 Jenkins → 凭据 → 系统 → 全局 → 添加凭据**

- 类型：**带密码的用户名**
- 用户名：您的 GitHub 用户名
- 密码：您的 PAT
- ID：**github-pat**（请在流水线中保留此确切 ID）
- save

**用途：** 允许 Jenkins 使用 Git 插件执行经过身份验证的 Git 检出。

------

#### **3. 在您的笔记本电脑/客户机上准备项目。**



我们希望采用生产环境式的流程：在本地准备好源代码，然后推送到私有仓库。

在您的文件夹内（例如`java-maven`：）：

```bash
git init
git add .
git commit -m "Initial commit with project code"
git branch -v
git branch -M main
```

## 命令详解

### 1. `git init`

- **作用**：在当前目录下初始化一个新的 Git 仓库
- **结果**：创建一个隐藏的 `.git` 文件夹，用于存储版本历史、配置等信息
- **执行后**：当前目录开始被 Git 追踪，可以进行版本管理

### 2. `git add .`

- **作用**：将当前目录（包括子目录）下的**所有更改**添加到暂存区（Staging Area）
- **细节**：
	- `.` 代表当前目录
	- 包括：新文件、修改过的文件、删除的文件
	- 暂存区相当于一个"提交预备区"，只有在这里面的文件才会被下一次提交包含

### 3. `git commit -m "Initial commit with project code"`

- **作用**：将暂存区中的所有内容提交到本地仓库，形成一个版本快照
- **参数**：
	- `-m`：提供提交信息（message）
	- `"Initial commit..."`：提交说明，描述这次更改的内容
- **结果**：生成一个唯一的 commit ID（如 `a3b5f9d`），记录当前代码状态

### 4. `git branch -v`

- **作用**：查看所有本地分支及其最后一次提交的信息

- **输出示例**：

	text

	```
	* main  a3b5f9d Initial commit with project code
	```

	

	- `*` 表示当前所在分支
	- `main` 是分支名
	- `a3b5f9d` 是最新提交的 ID
	- `Initial commit...` 是提交信息

### 5. `git branch -M main`

- **作用**：强制重命名当前分支为 `main`
- **参数**：
	- `-M`：大写 M，表示强制重命名（即使目标分支名已存在也会覆盖）
	- `main`：新的分支名称
- **背景**：早期 Git 默认分支名为 `master`，现在很多团队和平台（如 GitHub）改用 `main` 作为主分支名
- **使用场景**：如果当前分支是 `master`，这条命令会将其重命名为 `main`

------

**首次推送请使用临时令牌（仅限一次）。** 请使用描述性的远程仓库名称，以便您知道哪个远程仓库是私有仓库。您也可以`origin`根据需要使用其他方式。

```shell
# 添加一个指向你私有仓库的远程源
git remote add private-repo 
https://github.com/zj320900-glitch/zhoujie-private-repo.git

# 临时设置带令牌的远程 URL，以便安全推送
git remote set-url private-repo https://<TOKEN>@github.com/zj320900-glitch/zhoujie-private-repo.git

# 推送 main 分支
git push private-repo main
```



**立即从远程 URL 中删除令牌。** 这样可以防止令牌存储在本地 git 配置或 shell 历史记录中。

```shell
# restore the remote URL to the token-free form
git remote set-url private-repo https://github.com/zj320900-glitch/zhoujie-private-repo.git
```



------

#### **4. 验证存储库内容**



请确保您的私有仓库包含：`pom.xml`，`src`（Java 代码），稍后您将添加`Dockerfile`，，`deploy-svc.yaml`和`Jenkinsfile`。

------

#### **5. 创建 Jenkins Pipeline 作业**



路径：**Jenkins → 新建项目 → 流水线**

- 姓名 →`zhoujie-devsecops-demo`

- 类型 →**pipeline**

- 滚动至“pipeline”部分

	- 定义：**来自 SCM 的流水线脚本**

	- 源代码管理：**Git**

	- 仓库网址：

		```
		https://github.com/<your-username>/<your-private-repo>.git
		```

		建立的凭证类型选择username with password，用户名是github的用户名zj320900-glitch，密码就是token，ID是github-pat

	- 凭证 → 选择**github-pat**

	- 分支 →`*/main`

	- 脚本路径 → `Jenkinsfile`（默认）

点击**保存**。

------

#### **6. 运行第一个流水线构建**



点击**“立即构建”**。

预期行为：

- Jenkins 使用 Git 插件。
- Jenkins 通过**github-pat**进行身份验证。
- Jenkins 检查指定分支是否存在`Jenkinsfile`.
- 如果`Jenkinsfile`找到 a，则作业将检出工作区并继续进行下一阶段。
- 如果`Jenkinsfile`缺少 a，则作业将立即失败`Unable to find Jenkinsfile`（目前这是预期行为）。

------

## **第二阶段：Trivy FS 扫描（文件系统漏洞扫描）**



*（参考：管道图中的“Trivy FS Scan”框）*

### **客观的**



**使用Trivy**对源代码目录运行**文件系统级别的漏洞扫描**。此阶段可确保我们在构建镜像之前尽早发现**依赖项 CVE、操作系统软件包问题、错误配置和敏感信息。**

Trivy 安装在**Jenkins 代理虚拟机**上，而不是控制器上。

------

### **既然我们已经运行了 SonarQube SAST，为什么还要运行 Trivy FS 扫描呢？**



**SonarQube SAST** 扫描**源代码**，查找不安全模式、代码异味、不良实践、注入风险和安全热点。

**Trivy FS（SCA + 更多功能）** 扫描**运行时工件**；操作系统软件包、JVM/依赖项清单、供应商库、Dockerfile 和配置文件。执行**软件成分分析 (SCA)**以检测与 CVE 关联的库漏洞，以及 SAST 无法发现的错误配置和已知秘密模式。

**结果：** 两者结合使用可提供**纵深防御**——SAST 可提高代码质量和逻辑安全性；Trivy 可保护第三方库和运行时组件。

------

### **将第二阶段添加到 Jenkinsfile 中**



`Jenkinsfile`我们在您的项目文件夹（ ）中创建一个`jenkins-maven`，并定义一个早期安全门。

```
pipeline {
  agent { label 'docker-maven-trivy' }
  stages {
    stage('Trivy FS Scan') {
      steps {
        sh 'trivy fs --exit-code 1 --severity HIGH,CRITICAL .'
      }
    }
  }
}
```



### **解释**



- `pipeline {}` 整个 Jenkins Pipeline 以代码块形式呈现。

- `agent { label 'docker-maven-trivy' }`**选择已安装Docker、Maven 和 Trivy** 的 Jenkins 代理。

- `stage('Trivy FS Scan')` 创建专用的安全门，用于文件系统漏洞扫描。

- `sh 'trivy fs ...'` 对**当前工作区目录**运行 Trivy 。

	> 作业在 Jenkins 控制器上创建，但在**代理上的临时工作区**中运行。

- `--exit-code 1` 起到安全门的作用：当发现达到阈值的漏洞时，强制**整个构建流程失败。**

- `--severity HIGH,CRITICAL` 只针对**高影响问题**进行阻塞，保持流程的实用性并减少噪音。

------

### **在 Jenkins Agent VM 上安装 Trivy**



您的代理程序必须安装 Trivy 才能执行 Trivy 命令。请使用 SSH 连接到代理程序：

```
ssh -i <your-key>.pem ubuntu@<jenkins-agent-public-ip>
```



使用 Debian 软件包安装 Trivy（推荐）：

```shell
# install wget
sudo apt-get install -y wget

# download specific Trivy release
wget https://github.com/aquasecurity/trivy/releases/download/v0.69.3/trivy_0.69.3_Linux-64bit.deb

# install the package
sudo dpkg -i trivy_0.67.2_Linux-64bit.deb

# verify installation
trivy --version
```



**笔记：**

- 请定期更新 Trivy 以获取最新的 CVE 定义。
- 您的 Jenkins 代理标签已配置为将作业路由到此处。

------

### **提交并推送 Jenkinsfile**



```bash
git add .   #提交到暂存区
git commit -m "Jenkinsfile with Stage: 2"  #提交到本地仓库
git push private-repo main    #推送到远程仓库
```



------

### **运行流水线**



转到 Jenkins →**立即构建**。您现在将看到以下内容：

- 第一阶段 → Git检出
- 第二阶段 → Trivy FS 扫描

两者都应该顺利通过。

------

### **Trivy FS 报告示例（全新运行）**



```
Report Summary
┌─────────┬──────┬─────────────────┬─────────┐
│ Target  │ Type │ Vulnerabilities │ Secrets │
├─────────┼──────┼─────────────────┼─────────┤
│ pom.xml │ pom  │        0        │    -    │
└─────────┴──────┴─────────────────┴─────────┘
Legend:
- '-': Not scanned
- '0': Clean (no security findings detected)
```



> Trivy之所以**没有发现任何问题，**是因为它扫描的是依赖项、操作系统软件包和已知的安全模式，而不是源代码逻辑或不良实践。Java 文件中的问题是**SAST/风格/逻辑**问题（例如硬编码字符串、错误的字符串比较、资源泄漏），这些问题会被 SonarQube 标记出来，而不是 Trivy。建议同时使用这两个工具：**SonarQube 用于代码问题**，**Trivy 用于 CVE 和安全模式**。

------

### 模拟问题



如果您希望 Trivy 在文件系统扫描期间发现问题，请引入**故意设置的漏洞**：

- **添加易受攻击的依赖项**——插入`<dependencies>`到`pom.xml`：

```
<dependency>
  <groupId>org.apache.logging.log4j</groupId>
  <artifactId>log4j-core</artifactId>
  <version>2.14.0</version>
</dependency>
```



- **添加已知秘密模式**— 创建`secrets.txt`方式：

```
AWS_SECRET_ACCESS_KEY=AKIAAAAAAAAAAAAAAAAA
```



```bash
git add .
git commit -m "added trivy stage: fixed"
git push private-repo main
```

提交并推送，运行流水线，观察 Trivy 是否失败并显示测试结果。测试完成后，移除存在漏洞的依赖项，`secrets.txt`然后再次提交并推送。

------

## **第三阶段：构建和Sonar（Maven 构建、SAST 扫描、覆盖范围强制执行）**



*（参考：管道图中的“建造和Sonar”框）*

### **客观的**



**使用Maven**编译应用程序，运行**单元测试**，收集**测试覆盖率**，并使用**SonarQube执行****SAST 分析**。此阶段会强制执行**质量门控**，如果项目未达到所需的代码覆盖率或安全标准，则流水线将失败。

我们使用**JUnit**、**Surefire**和**Jacoco**插件来生成 SonarQube 使用的报告。

Jenkins 通过**私有 IP**与 SonarQube 通信，这在生产部署中很常见。

------

### **1. 通过 SSH 连接到 SonarQube 服务器**



```bash
ssh -i <your-key>.pem ubuntu@<sonarqube-public-ip>
```



这样就可以配置项目、生成令牌并创建质量门。

------

### **2. 创建 SonarQube 项目**（当一个项目被创建时，它会自动与默认的质量门配置文件sonar way关联）



SonarQube 用户界面 →**项目 → 创建项目**

- 项目显示名称 →`zhoujie-devsecops-demo`
- 项目密钥 →`zhoujie-devsecops-demo`

这是 Maven 命令中使用的唯一标识符。

------

### **3. 创建自定义质量门**(创建新的质量门，会把默认质量门Sonar Way的条件复制过来)



SonarQube →**质量门 → 创建**

- 姓名 →`zhoujie-devsecops-demo-qg`
- 添加条件 → coverage
	- where：**On Overall Code**
	- 运算符：**小于**
	- value：**80**
	- without：勾选

将您的项目（`zhoujie-devsecops-demo`）添加到此质量门。

目的：如果整体覆盖率低于 80%，则流水线失败。这样可以在演示过程中预测构建失败的情况。

------

### **4. 为 Jenkins 生成 SonarQube Token**

login: sonar-qube password:    Name:sonar-qube Email: abc@xyz.com,在groups里面勾选unselected下的sonar-administrators，点击done，点击tokens，token名字设置jenkins-sonar，点击generate生成token，注意token只生产一次，要注意保存

SonarQube → Administration → Security → Users → **Create User**

- 机器帐户名 →`jenkins-sonar`或`sonar-user`.
- 分配所需的最低限度的组/权限（除非必要，否则避免授予完全管理员权限）。

**在“生成令牌”**下，创建一个名为 的令牌`jenkins-token`。 **原因：**创建此令牌是为了让**Jenkins 代理能够向 SonarQube 进行身份验证**，并在流水线期间上传分析结果。

**立即复制令牌**——你只会看到它一次。

------

### **5. 将令牌添加到 Jenkins 凭据**



Jenkins →**管理 Jenkins → 凭据 → 系统 → 全局 → 添加凭据**。

- 类型：****Secret Text****变量写：SONARQUBE-TOKEN
- secret：`<sonarqube-token>`
- ID: **sonarqube-token** **原因:**以便Jenkins 代理可以在流水线期间**向 SonarQube 进行身份验证并上传分析结果。**

使用**管道语法**→ **withCredentials**在运行时将令牌绑定到变量中：

- 示例步骤 → withCredentials：将凭据绑定到变量 → 选择**密钥文本**→ 变量名称`SONAR_TOKEN`（保持相关） → 凭据：选择`sonarqube-token`您创建的凭据 → 生成管道脚本。

可在构建和Sonar阶段使用的示例生成代码块：

```
withCredentials([string(credentialsId: 'sonarqube-token', variable: 'SONAR-TOKEN')]) {
    // some block
}
```



您将`withCredentials`在 Maven Sonar 命令周围使用此代码块，以便管道安全地将令牌传递给 SonarQube。

> **安全注意事项：**使用`withCredentials`（密钥文本 / `string`）是向流水线提供令牌的正确方法；Jenkins 的凭据绑定插件在通过绑定使用时，会在控制台输出和日志中**屏蔽/编辑**密钥。

------

### **6. 在 Jenkins 中添加 Maven 工具**



Jenkins → 管理 Jenkins → 全局工具配置 → Maven，点击新增maven

- 姓名 →`maven3`
- 检查**自动安装**（从Apache安装）
- 选择 Apache Maven 版本（适用于在线环境）

您**可以**手动在 Jenkins 上安装 Maven `jenkins-agent`，但 Jenkins 也支持通过全局工具配置插件自动安装（方便演示和在线代理）。如果您的环境处于**离线状态**或互联网访问受限，**请预先在代理上安装 Maven**，并将工具配置指向该本地路径。

------

## **包含第三阶段的pipeline**



```
pipeline {
  agent { label 'docker-maven-trivy' }
  tools {
    maven 'maven3'
  }
  environment {
    SONAR_IP = '3.148.193.21'
  }
  stages {
    stage('Trivy FS Scan') {
      steps {
        sh 'trivy fs --exit-code 1 --severity HIGH,CRITICAL .'
      }
    }
    stage('Build & Sonar') {
      steps {
       withCredentials([string(credentialsId: 'sonarqube-token', variable: 'SONAR_TOKEN')])  {
          sh 'mvn clean verify sonar:sonar \
  -Dsonar.projectKey=zhoujie-devsecops-demo \
  -Dsonar.host.url="http://${SONAR_IP}:9000" \
  -Dsonar.token="${SONAR_TOKEN}" \
  -Dsonar.qualitygate.wait=true'
        }
      }
    }
  }
}

```

sonar:sonar表示我在调用sonar插件，并在该插件中调用sonar目标

```bash
git add .
git commit -m "build and sonar"
git push private-repo main
```



------

### **说明（第三阶段特定模块）**



#### **工具 { maven 'maven3' }**



- 告诉 Jenkins 配置 Maven（之前配置的版本）。
- Jenkins 会自动`mvn`将此构建注入到 PATH 环境变量中。

------

#### **环境 { SONAR_IP = '172.31.21.44' }**



- 存储 SonarQube 服务器的私有 IP 地址。
- **也可以在舞台块内**定义，但为了便于学习，所有变量都集中在一个地方，因此需要全局定义。

> **注意（生产环境）：**使用**DNS 名称**（例如`sonar.internal.company.local`）而不是 IP 地址；DNS 可以实现证书验证、更轻松的轮换和环境可移植性。

------

### **建造与声呐阶段**



```
stage('Build & Sonar') {
  steps {
   withCredentials([string(credentialsId: 'sonarqube-token', variable: 'SONAR_TOKEN')]) {
      sh 'mvn clean verify sonar:sonar \
-Dsonar.projectKey=zhoujie-devsecops-demo \
-Dsonar.host.url="http://${SONAR_IP}:9000" \
-Dsonar.token="${SONAR_TOKEN}" \
-Dsonar.qualitygate.wait=true'
    }
  }
}
```



#### **使用凭据**



- 将 Sonar 令牌安全地注入到环境中`SONAR_TOKEN`。
- 确保令牌永远不会出现在日志或管道历史记录中。

#### **mvn clean verify sonar:sonar**



分解：

- `clean`→ 移除旧的构建产物
- `verify`→ 运行 compile、test、surefire 和 jacoco 代码覆盖率测试
- `sonar:sonar`→ 运行 SonarQube SAST，上传覆盖率，并触发质量门评估

声呐参数：

- `-Dsonar.projectKey`→ 指向我们的项目
- `-Dsonar.host.url`→ SonarQube 服务器 URL
- `-Dsonar.token`→ CI 的身份验证令牌
- `-Dsonar.qualitygate.wait=true`
	- 流水线**等待**SonarQube 评估质量门。
	- 如果覆盖率或 SAST 规则违反了该门控，构建将立即**失败。**

------

### **运行流水线**



点击**“立即构建”**。

该管道将在第三阶段发生故障，原因是：

- 我们设置了严格的质量门，要求**覆盖率达到 80%**。
- 我们的演示项目没有足够的单元测试来满足这个要求。

在生产环境中，开发人员会在这里**修复测试覆盖率**或提高代码质量。

------

### **仅供演示之用**



为了继续推进管道建设，降低覆盖率阈值：

将质量门条件从更改`80`为`1`

**绝对不要在生产环境中这样做。**质量门的存在是为了保护系统完整性。

------

## **第四阶段：ECR 登录（对 Jenkins Agent 进行 Amazon ECR 身份验证）**

存储库名称设置为zhoujie-devsecops-demo，镜像标签设置为Immutable，不可变的标签排除项填写latest，这样就可以重复上传带latest标签的镜像，加密配置选择AES-256

*（参考：流程图中的“ECR登录”框）*

### **客观的**



对 Jenkins 代理进行身份验证，使其能够访问您的**Amazon ECR**私有镜像仓库，以便流水线可以推送 Docker 镜像。我们安装**AWS CLI**，为代理配置正确的**IAM 权限**`aws ecr get-login-password`，创建 ECR 仓库，然后使用.

------

## **前提条件：在 Jenkins Agent 上安装 AWS CLI 和 Docker Engine。**



我们需要`aws-cli`代理运行 AWS 特定的命令。通过 SSH 连接到**Jenkins 代理虚拟机**：

### **1) 安装 AWS CLI v2（系统范围）**



```bash
# 下载aws cli v2
curl "https://awscli.amazonaws.com/awscli-exe-linux-x86_64.zip" -o awscliv2.zip

# 安装 unzip
sudo apt install unzip

# 解压
unzip awscliv2.zip

# 系统范围安装，这样所有用户（包括 jenkins）都可以访问
sudo ./aws/install
```



验证安装：

```bash
aws --version
sudo -u jenkins aws --version
```



------

### **2) 在 Jenkins Agent 上安装 Docker Engine（官方步骤，简要说明）**



参考：[https ://docs.docker.com/engine/install/ubuntu/](https://docs.docker.com/engine/install/ubuntu/)

````shell
# 如果系统中存在旧版本的 Docker 软件包，进行移除（可安全执行）
sudo apt remove docker docker-engine docker.io containerd runc -y

# 刷新 apt 软件包缓存（务必首先执行此步骤）
sudo apt update

# 安装 Docker 仓库设置所需的辅助软件包
sudo apt install -y ca-certificates curl gnupg

# 创建 apt 密钥存储目录并设置正确权限（0755）
sudo install -m 0755 -d /etc/apt/keyrings

# 获取 Docker GPG 密钥并存储到密钥存储目录（安全，只读）
curl -fsSL https://download.docker.com/linux/ubuntu/gpg | \
  sudo gpg --dearmor -o /etc/apt/keyrings/docker.gpg

# 确保 Docker 密钥文件全局可读，以便 apt 能够正常使用
sudo chmod a+r /etc/apt/keyrings/docker.gpg

# 为当前 Ubuntu 版本代号添加 Docker apt 仓库
echo \
  "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.gpg] \
  https://download.docker.com/linux/ubuntu \
  $(. /etc/os-release && echo \"$VERSION_CODENAME\") stable" | \
  sudo tee /etc/apt/sources.list.d/docker.list > /dev/null

# 刷新软件包列表，使 Docker 仓库生效
sudo apt update

# 安装 Docker 引擎、CLI、containerd 及相关插件
sudo apt install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin

````



```shell
# 将 'jenkins' 系统用户添加到 docker 用户组，这样构建任务就可以无需 sudo 直接运行 docker 命令
sudo usermod -aG docker jenkins

# 可选：同时也将 ubuntu 用户添加到 docker 组，以便管理员可以使用 ubuntu 用户账户进行演示操作
sudo usermod -aG docker ubuntu
```



```shell
# 验证当前用户能否正常使用 Docker CLI（本地快速检查）
docker --version

# 验证以 jenkins 用户身份运行时 Docker CLI 是否可用（非 root 用户检查）
sudo -u jenkins docker --version
```



**笔记：**

- `usermod -aG docker jenkins`会话结束后，`jenkins`必须重新登录或代理重新连接才能使组成员身份生效。
- 生产环境中，请使用安全的 Docker 远程 API；不要将其暴露`/var/run/docker.sock`给不受信任的容器。

------

## **前提条件：授予 Jenkins Agent 对 Amazon ECR 的访问权限**



由于 Jenkins 代理运行在**EC2 实例**上，最佳实践是使用**IAM 角色**，而不是长期有效的 AWS 访问密钥。

### **为 Jenkins Agent 创建 IAM 角色**



AWS 控制台 → **IAM → 角色 → 创建角色**

- 选择**AWS 服务**
- 使用案例 → **EC2**
- 附加策略 → **搜索框内输入container，勾选AmazonEC2ContainerRegistryPowerUser，点击下一步**
- 角色名称 →`jenkins-agent-role，点击创建角色`

### **将角色附加到 Jenkins Agent EC2 实例**



AWS 控制台 → **EC2 → 实例 → Jenkins agent → 操作 → 安全 → 修改 IAM 角色**

- 选择 →`jenkins-agent-role`
- save

这将授予代理对 ECR 进行身份验证、拉取/推送映像以及执行注册表操作的权限。

------

### **创建 ECR 存储库**



AWS 控制台 → **Amazon ECR → 私有注册表 → 创建存储库**

- 姓名 →`zhoujie-devsecops-demo`
- 图片标签设置 →**不可更改**
	- 优点：防止意外覆盖，提高可审计性，并保持部署历史记录的清晰。
- 不可更改的标签排除项 →`latest`
	- 允许`latest`在演示过程中反复推送标签。
- 保持其他设置默认 →**创建**

从仓库 →**查看推送命令，**可以看到登录代码片段：

```bash
aws ecr get-login-password --region ap-south-1 | docker login --username AWS --password-stdin 386275436648.dkr.ecr.ap-south-1.amazonaws.com
```



------

### **包含 ECR 登录的pipeline（第 4 阶段）**

如果你的Jenkins是部署在AWS外的，你可以使用AWS秘密密钥或有其他办法来访问AWS ECR，Jenkins部署在AWS内部就可以使用IAM来进行访问

```
pipeline {
  agent { label 'docker-maven-trivy' }
  tools {
    maven 'maven3'
  }
  environment {
    SONAR_IP = '3.148.193.21'
    ECR_REGISTRY = '253685958295.dkr.ecr.us-east-2.amazonaws.com'
  }
  stages {
    stage('Trivy FS Scan') {
      steps {
        sh 'trivy fs --exit-code 1 --severity HIGH,CRITICAL .'
      }
    }
    stage('Build & Sonar') {
      steps {
        withCredentials([string(credentialsId: 'sonarqube-token', variable: 'SONAR_TOKEN')]) {
          sh 'mvn clean verify sonar:sonar \
  -Dsonar.projectKey=zhoujie-devsecops-demo \
  -Dsonar.host.url="http://${SONAR_IP}:9000" \
  -Dsonar.token="${SONAR_TOKEN}" \
  -Dsonar.qualitygate.wait=true'
        }
      }
    }
    stage('ECR Login') {
      steps {
        sh 'aws ecr get-login-password --region us-east-2  | docker login --username AWS --password-stdin $ECR_REGISTRY'
      }
    }
  }
}
```

注意--region要和ECR_REGISTRY中的us-east-2相同，否则会失败

------

### **解释**



- `environment { ECR_REGISTRY = '386275436648.dkr.ecr.ap-south-1.amazonaws.com' }` 存储私有 ECR 注册表主机名，这样我们就可以避免在阶段内重复使用长 URL。
- `aws ecr get-login-password --region ap-south-1` 获取用于 Docker 登录的**短期身份验证令牌。**
- `| docker login --username AWS --password-stdin $ECR_REGISTRY` 将 ECR 令牌直接通过管道传输到 Docker，实现安全、非交互式登录。
- 组合登录命令可确保 Jenkins 代理安全地进行身份验证，无需暴露凭证，也无需 AWS 访问密钥。此方法之所以能够无缝运行，是因为 EC2 实例使用`jenkins-agent-role`具有 ECR 权限的 IAM 角色。

------

### **运行流水线**



点击**“立即构建”**。

如果已安装 AWS CLI、IAM 角色已正确附加且存储库存在，则**ECR 登录**阶段将成功完成。如果登录失败，请检查：

- 已附加 IAM 角色
- IAM 策略包括`AmazonEC2ContainerRegistryPowerUser`
- AWS CLI 对 root 用户和 Jenkins 用户均可访问
- ECR_REGISTRY 值与您的存储库匹配

------

## **第五阶段：构建镜像（构建容器镜像）**



*（参考：流水线图中的“构建映像”框）*

### **客观的**



在 Jenkins 代理上安装**Docker Engine**`jenkins` ，确保用户可以运行 Docker，并构建一个 Docker 镜像，该镜像将在后续阶段推送到 ECR。我们添加了一个`IMAGE_REPO`环境变量，以确保各阶段镜像命名的一致性。

------

## 创建 Dockerfile

可以sudo -u jenkins bash进入jenkins家目录查看workspace

`Dockerfile`在项目根目录下创建`java-maven`包含以下内容的文件：

```dockerfile
FROM eclipse-temurin:21 #这个基础镜像bug太多，推荐使用下面的镜像
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY target/zhoujie-devsecops-demo-1.0.0-SNAPSHOT.jar app.jar
RUN apk update && apk upgrade   # 修复 Alpine 现有漏洞
EXPOSE 8080
ENTRYPOINT ["java","-jar","app.jar"]

```



------

### **简洁明了的解释**



- `FROM eclipse-temurin:21` 使用 Eclipse Temurin Java 21 运行时作为小型、维护良好的基础镜像。
- `WORKDIR /app` 将镜像内部的工作目录设置为`/app`.
- `COPY target/zhoujie-devsecops-demo-1.0.0-SNAPSHOT.jar app.jar` 将 Maven`target`文件夹中构建的 JAR 文件复制到镜像中`app.jar`。
- `EXPOSE 8080` 记录容器在运行时监听端口 8080 的信息。
- `ENTRYPOINT ["java", "-jar", "app.jar"]` 容器启动时运行 JAR 文件——这是应用程序的入口点。

------

### **承诺并努力**



文件夹中的内容`java-maven`：

```bash
git add .  
git commit -m "added Dockerfile"  
git push private-repo main  
```



------

## **包含构建镜像阶段的流水线（已添加 IMAGE_REPO）**

http://IP:8080/env-vars.html可以查看系统环境变量

```
pipeline {
  agent { label 'docker-maven-trivy' }
  tools {
    maven 'maven3'
  }
  environment {
    SONAR_IP = '3.148.193.21'
    ECR_REGISTRY = '253685958295.dkr.ecr.us-east-2.amazonaws.com'
    IMAGE_REPO = "${ECR_REGISTRY}/zhoujie-devsecops-demo"
  }
  stages {
    stage('Trivy FS Scan') {
      steps {
        sh 'trivy fs --exit-code 1 --severity HIGH,CRITICAL .'
      }
    }
    stage('Build & Sonar') {
      steps {
        withCredentials([string(credentialsId: 'sonarqube-token', variable: 'SONAR_TOKEN')]) {
          sh 'mvn clean verify sonar:sonar \
  -Dsonar.projectKey=cwvj-devsecops-demo \
  -Dsonar.host.url="http://${SONAR_IP}:9000" \
  -Dsonar.token="${SONAR_TOKEN}" \
  -Dsonar.qualitygate.wait=true'
        }
      }
    }
    stage('ECR Login') {
      steps {
        sh 'aws ecr get-login-password --region us-east-2 | docker login --username AWS --password-stdin $ECR_REGISTRY'
      }
    }
    stage('Build Image') {
      steps {
        sh 'export DOCKER_BUILDKIT=0 && docker build --platform linux/amd64 -t "$IMAGE_REPO:$BUILD_NUMBER" -t "$IMAGE_REPO:latest" .'
      }
    }
  }
}
```

注意：使用这个linux/amd64是因为EKS集群的woker节点也会使用amd架构，出现permission denied while trying to connect to the docker API at unix:///var/run/docker.sock，只需断开agent节点重连一下就可以，还不行的话重启一下agent服务器和contoller上的Jenkins服务

、1. `export DOCKER_BUILDKIT=0`

**作用**：禁用 Docker BuildKit

- `DOCKER_BUILDKIT=0`：设置环境变量，0 表示禁用 BuildKit，1 表示启用
- BuildKit 是 Docker v18.06+ 引入的新构建引擎，性能更好、功能更强
- 禁用 BuildKit 会回退到传统的 `docker build` 模式

**为什么可能禁用 BuildKit？**

- 传统模式兼容性更好

- 某些旧版 Dockerfile 语法在 BuildKit 下可能出问题

- 某些 CI/CD 环境对 BuildKit 支持不完善

	

------

### **解释**



- `environment { IMAGE_REPO = "${ECR_REGISTRY}/cwvj-devsecops-demo" }` 定义用于跨阶段标记和推送图像的图像存储库变量。

- `stage('Build Image')` 专用阶段，用于从管道工作区构建容器镜像。

- `sh 'export DOCKER_BUILDKIT=0 && docker build --platform linux/amd64 -t "$IMAGE_REPO:$BUILD_NUMBER" -t "$IMAGE_REPO:latest" .'` 在代理 shell 中运行构建命令。

- `export DOCKER_BUILDKIT=0` 禁用 Docker 的新型 BuildKit 引擎，使构建以经典的、可预测的模式运行。防止代理使用旧版 Docker 或混合 BuildKit 支持时出现意外行为。确保无论代理配置如何，都能获得相同的构建输出。

- `--platform linux/amd64` 确保镜像构建时采用**amd64**架构。这与您的**EKS 工作节点**相匹配，这些节点运行的是**x86_64**架构（Docker 将 x86_64 和 amd64 视为相同）。这可以防止意外构建多架构镜像或 arm64 镜像，避免这些镜像无法在您的集群上运行。此外，还能确保与Dockerfile 中的**基础镜像**`FROM eclipse-temurin:21`兼容，该基础镜像默认也是 amd64 架构。

	**简而言之：****EKS 节点**和**基础镜像**的架构必须一致，并`--platform linux/amd64`保证这种一致。

- `-t "$IMAGE_REPO:$BUILD_NUMBER"` 使用 Jenkins 为镜像添加标签，`BUILD_NUMBER`以实现不可变、可追溯的部署。

- `-t "$IMAGE_REPO:latest"``latest`同时，为了演示方便， 将图像标记为（存储库已配置为排除`latest`在不可变性之外）。

- `.`（构建上下文）使用存储库根目录作为 Docker 构建上下文；确保`.dockerignore`存在 a 以保持上下文较小。

------

### **pipeline运行**



点击**“立即构建”**。如果已安装 Docker 且 Jenkins 用户拥有该组的成员身份，“构建镜像”阶段将成功构建并标记镜像。如果失败，请检查：

- Docker 服务已激活（`sudo systemctl status docker`）。
- `jenkins`用户已加入`docker`群组，代理会话已检测到更改。
- 构建上下文很小，但`.dockerignore`存在。

------

## **第六阶段：Trivy 镜像扫描（容器镜像漏洞扫描）**



*（参考：流程图中的“简单图像扫描”框）*

### **客观的**



在推送或部署之前，请扫描**已构建的容器镜像是否存在漏洞。这可以确保实际运行时工件（运行在 Kubernetes Pod 中的 Docker 镜像）安全无虞，并且不包含高危或严重 CVE 漏洞。**

图像扫描是任何生产环境 DevSecOps 流程中必不可少的安全关卡。

------

## **为什么 Trivy 图像扫描很重要**



Trivy Image Scan 分析**最终的容器图像**，包括：

- 基础镜像中的操作系统软件包（Ubuntu、Alpine、Distroless 等）
- 镜像中捆绑了语言级别的依赖项
- 仅在运行时存在的系统库和实用程序
- 基础镜像引入的易受攻击的二进制文件或层

这一点至关重要，因为**开发人员很少能控制基础镜像**，但大多数漏洞都源于此。

------

## **Trivy图像扫描与以往扫描有何不同**



### **1. Trivy FS 扫描（对源文件夹进行 SCA 扫描）**



- 扫描您的*项目源代码文件夹*
- 检测`pom.xml`配置文件和供应商包中的依赖项漏洞
- 不**检查**最终容器图像

### **2. SonarQube SAST + 覆盖率**



- 检查您的**自定义应用程序代码**
- 发现不安全模式、代码异味、漏洞和质量问题
- 衡量单元测试覆盖率
- 无法**检测**操作系统级别的 CVE 或容器漏洞

### **3. Trivy图像扫描（此阶段）**



- 扫描存储在 Docker 本地缓存中的**完整镜像。**
- 查找基础镜像层和操作系统软件包中的漏洞
- 检测**源代码中不存在**、仅在构建后才出现的问题
- 确保生产运行时工件可以安全部署。

**简而言之：** 源代码级扫描保护您的代码，镜像扫描保护您的容器运行时。两者对于构建安全的管道都必不可少。

------

## **带有 Trivy 图像扫描阶段的pipeline**



```
pipeline {
  agent { label 'docker-maven-trivy' }
  tools {
    maven 'maven3'
  }
  environment {
    SONAR_IP = '3.148.193.21'
    ECR_REGISTRY = '253685958295.dkr.ecr.us-east-2.amazonaws.com'
    IMAGE_REPO = "${ECR_REGISTRY}/zhoujie-devsecops-demo"
  }
  stages {
    stage('Trivy FS Scan') {
      steps {
        sh 'trivy fs --exit-code 1 --severity HIGH,CRITICAL .'
      }
    }
    stage('Build & Sonar') {
      steps {
        withCredentials([string(credentialsId: 'sonarqube-token', variable: 'SONAR_TOKEN')]) {
          sh 'mvn clean verify sonar:sonar \
            -Dsonar.projectKey=zhoujie-devsecops-demo \
            -Dsonar.host.url="http://${SONAR_IP}:9000" \
            -Dsonar.token="${SONAR_TOKEN}" \
            -Dsonar.qualitygate.wait=true'
        }
      }
    }
    stage('ECR Login') {
      steps {
        sh 'aws ecr get-login-password --region us-east-2 | docker login --username AWS --password-stdin $ECR_REGISTRY'
      }
    }
    stage('Build Image') {
      steps {
        sh 'export DOCKER_BUILDKIT=0 && docker build --platform linux/amd64 -t "$IMAGE_REPO:$BUILD_NUMBER" -t "$IMAGE_REPO:latest" .'
      }
    }
    stage('Trivy Image Scan') {
      steps {
        sh 'trivy image --exit-code 1 --severity HIGH,CRITICAL "$IMAGE_REPO:$BUILD_NUMBER"'
      }
    }
  }
}

```



------

### **解释**



- `stage('Trivy Image Scan')` 创建专用的安全门，在部署前扫描已构建的 Docker 镜像。
- `trivy image ... "$IMAGE_REPO:$BUILD_NUMBER"` 扫描我们在上一阶段构建的**确切图像**。使用不可变标签（`$BUILD_NUMBER`）以避免扫描错误的版本。
- `--exit-code 1`如果发现高危或严重漏洞， 则使管道运行**失败。**
- `--severity HIGH,CRITICAL` 重点关注部署前必须解决的严重问题。

------

### **结果**



此阶段验证容器镜像是否安全，确保其适用于生产环境。如果发现漏洞：

- 管道故障
- 你**没有**推送图片。
- 您**无需**将其部署到 EKS。

这可以建立强大的安全边界，确保只有安全的容器工件才能继续传输。

------

## **第七阶段：推送至 ECR（将构建好的镜像推送至 Amazon ECR）**



*（参考：管道图中的“推送至 ECR”框）*

### **客观的**



将构建并标记好的 Docker 镜像推送到 Amazon ECR 仓库，使其可用于部署。我们同时推送不可变的构建标签（`$BUILD_NUMBER`）和便捷`latest`标签。

------

### **pipeline片段**



```
pipeline {
  agent { label 'docker-maven-trivy' }
  tools {
    maven 'maven3'
  }
  environment {
    SONAR_IP = '3.148.193.21'
    ECR_REGISTRY = '253685958295.dkr.ecr.us-east-2.amazonaws.com'
    IMAGE_REPO = "${ECR_REGISTRY}/zhoujie-devsecops-demo"
  }
  stages {
    stage('Trivy FS Scan') {
      steps {
        sh 'trivy fs --exit-code 1 --severity HIGH,CRITICAL .'
      }
    }
    stage('Build & Sonar') {
      steps {
        withCredentials([string(credentialsId: 'sonarqube-token', variable: 'SONAR_TOKEN')]) {
          sh 'mvn clean verify sonar:sonar \
            -Dsonar.projectKey=zhoujie-devsecops-demo \
            -Dsonar.host.url="http://${SONAR_IP}:9000" \
            -Dsonar.token="${SONAR_TOKEN}" \
            -Dsonar.qualitygate.wait=true'
        }
      }
    }
    stage('ECR Login') {
      steps {
        sh 'aws ecr get-login-password --region us-east-2 | docker login --username AWS --password-stdin $ECR_REGISTRY'
      }
    }
    stage('Build Image') {
      steps {
        sh 'export DOCKER_BUILDKIT=0 && docker build --platform linux/amd64 -t "$IMAGE_REPO:$BUILD_NUMBER" -t "$IMAGE_REPO:latest" .'
      }
    }
    stage('Trivy Image Scan') {
      steps {
        sh 'trivy image --exit-code 1 --severity HIGH,CRITICAL "$IMAGE_REPO:$BUILD_NUMBER"'
      }
    }
    stage('Push to ECR') {
      steps {
        sh 'docker push "$IMAGE_REPO:$BUILD_NUMBER"'
        sh 'docker push "$IMAGE_REPO:latest"'
      }
    }
  }
}
```



------

### **为什么这个阶段很重要**



- 推送在先前阶段扫描和验证过的确切工件。
- 该`$BUILD_NUMBER`标签为回滚操作提供了一个不可变、可追溯的镜像。
- 该`latest`标签可用于迭代测试和演示（在仓库设置中排除不可变性）。

------

### **解释**



- `stage('Push to ECR')` 专用阶段，用于将本地镜像上传到远程 ECR 注册表。
- `docker push "$IMAGE_REPO:$BUILD_NUMBER"` 推送特定构建镜像。这是要推广到各个环境的标准镜像。
- `docker push "$IMAGE_REPO:latest"` 为了方便起见，推送了`latest`标签；仓库已配置为排除`latest`在不可变性之外。
- 成功的前提条件
	- ECR登录阶段已成功完成。
	- `IMAGE_REPO`已正确定义（`<account>.dkr.ecr.<region>.amazonaws.com/<repo>`）。
	- Jenkins代理IAM角色具有ECR推送权限。
	- Docker守护进程正在运行，并且具有指定标签的镜像已存在于本地。

------

### **核实**



1. 打开 AWS 控制台 → **Amazon ECR** → 选择存储库`zhoujie-devsecops-demo`。
2. 点击**“图片”**（或**“查看推送命令”** → **“查看图片”**），确认存在两个标签：
	- `<BUILD_NUMBER>`（例如，`42`）
	- `latest`
3. 确认图像摘要和推送时间戳，以便进行追溯。

如果缺少标签，请检查管道日志中的错误，并确保在推送之前已在本地构建和标记镜像。

------

## **第八阶段：创建 Kubernetes 清单（Deployment + Service）**


*（参考：管道图中的“创建清单”框）*

### **客观的**



为应用程序创建 Kubernetes 清单：一个包含**2 个副本**的 Deployment和一个 NodePort Service。添加`topologySpreadConstraints`配置，使 Pod 分布在不同的可用区。将清单提交到代码仓库，以便流水线稍后可以更新它们。

------

### **部署（deploy-svc.yaml）**



```yaml
apiVersion: apps/v1  
kind: Deployment  
metadata:  
  name: zhoujie-devsecops-demo  
  namespace: zhoujie-devsecops
  labels:  
    app: zhoujie-devsecops-demo  
spec:  
  replicas: 2  
  selector:  
    matchLabels:  
      app: zhoujie-devsecops-demo  
  template:  
    metadata:  
      labels:  
        app: zhoujie-devsecops-demo  
    spec:  
      topologySpreadConstraints:  
        - maxSkew: 1  
          topologyKey: topology.kubernetes.io/zone  
          whenUnsatisfiable: DoNotSchedule  
          labelSelector:  
            matchLabels:  
              app: zhoujie-devsecops-demo  
      containers:  
        - name: zhoujie-devsecops-demo  
          image: 253685958295.dkr.ecr.us-east-2.amazonaws.com/zhoujie-devsecops-demo:23  
          ports:  
            - containerPort: 8080  
```



------

### **服务（deploy-svc.yaml — 同一文件或单独文件）**



```yaml
apiVersion: v1  
kind: Service  
metadata:  
  name: zhoujie-devsecops-demo-svc  
  namespace: zhoujie-devsecops
  labels:  
    app: zhoujie-devsecops-demo  
spec:  
  type: NodePort  
  selector:  
    app: zhoujie-devsecops-demo  
  ports:  
    - port: 80  
      targetPort: 8080  
      protocol: TCP  
      nodePort: 31000  
```



------

### **拓扑传播约束 — 说明**



- 目的：将 pod 均匀分布在拓扑域（此处为可用区）中，以提高可用性并减少爆炸半径。
- `maxSkew: 1` 拓扑域之间 Pod 数量允许的最大差异。对于 2 个副本，`maxSkew: 1`强制执行 1:1 的均匀分布。
- `topologyKey: topology.kubernetes.io/zone` 节点标签用作分布轴。这指示调度器将 Pod 分布到不同的可用区。
- `whenUnsatisfiable: DoNotSchedule` 如果放置 Pod 会违反约束条件，调度器将不会放置它。这强制执行严格的隔离。
- `labelSelector.matchLabels` 将传播规则限制在具有`app: cwvj-devsecops-demo`以下条件的 pod 中：其他工作负载不受影响。

------

### **pipeline片段**



```
pipeline {
  agent { label 'docker-maven-trivy' }
  tools {
    maven 'maven3'
  }
  environment {
    SONAR_IP = '3.148.193.21'
    ECR_REGISTRY = '253685958295.dkr.ecr.us-east-2.amazonaws.com'
    IMAGE_REPO = "${ECR_REGISTRY}/zhoujie-devsecops-demo"
  }
  stages {
    stage('Trivy FS Scan') {
      steps {
        sh 'trivy fs --exit-code 1 --severity HIGH,CRITICAL .'
      }
    }
    stage('Build & Sonar') {
      steps {
        withCredentials([string(credentialsId: 'sonarqube-token', variable: 'SONAR_TOKEN')]) {
          sh 'mvn clean verify sonar:sonar \
            -Dsonar.projectKey=zhoujie-devsecops-demo \
            -Dsonar.host.url="http://${SONAR_IP}:9000" \
            -Dsonar.token="${SONAR_TOKEN}" \
            -Dsonar.qualitygate.wait=true'
        }
      }
    }
    stage('ECR Login') {
      steps {
        sh 'aws ecr get-login-password --region us-east-2 | docker login --username AWS --password-stdin $ECR_REGISTRY'
      }
    }
    stage('Build Image') {
      steps {
        sh 'export DOCKER_BUILDKIT=0 && docker build --platform linux/amd64 -t "$IMAGE_REPO:$BUILD_NUMBER" -t "$IMAGE_REPO:latest" .'
      }
    }
    stage('Trivy Image Scan') {
      steps {
        sh 'trivy image --exit-code 1 --severity HIGH,CRITICAL "$IMAGE_REPO:$BUILD_NUMBER"'
      }
    }
    stage('Push to ECR') {
      steps {
        sh 'docker push "$IMAGE_REPO:$BUILD_NUMBER"'
        sh 'docker push "$IMAGE_REPO:latest"'
      }
    }
    stage('Update Deployment') {
      steps {
        sh 'sed -i "s|image:.*|image: $IMAGE_REPO:$BUILD_NUMBER|g" deploy-svc.yaml'
      }
    }
  }
}  
```



### **`sed`命令——逐步解释**



管道中后续使用的命令：

```
sed -i "s|image:.*|image: $IMAGE_REPO:$BUILD_NUMBER|g" deploy-svc.yaml
```



1. `sed -i`**直接** 编辑文件，以便将更改直接写入`deploy-svc.yaml`。
2. `s|image:.*|image: $IMAGE_REPO:$BUILD_NUMBER|g`
	- `s`—`sed`替换命令。
	- `|`— 选择此分隔符是为了避免转义图像 URL 中的斜杠。
	- `image:.*`— 正则表达式匹配`image:`该行中任意一行及其之后的所有内容。
	- `image: $IMAGE_REPO:$BUILD_NUMBER`— 替换文本；shell 展开`$IMAGE_REPO`，并在运行`$BUILD_NUMBER`前`sed`执行。
	- `g`— 全局标志；替换文件中的所有匹配项（如果`image:`存在多个条目则安全）。
3. `deploy-svc.yaml` 要更新的目标清单文件。

**最终效果：**将每一`image:`行替换为精确的构建镜像`${IMAGE_REPO}:${BUILD_NUMBER}`，确保 Kubernetes 将拉取此流水线运行生成的镜像。

------

### **提交清单**



```bash
git add .  
git commit -m "uploaded deployment and svc manifest"  
git push private-repo main  
```



------

## **第九阶段：部署到 Kubernetes（创建集群、授予访问权限、部署应用程序）**



*（参考：流水线图中的“部署到 Kubernetes”框）*

### **客观的**



（如果尚未存在）配置 EKS 集群，为 Jenkins 代理分配所需的最低 IAM 和 Kubernetes RBAC 权限，`kubectl`在代理上安装相关工具，并将`deploy-svc.yaml`清单文件部署到`zhoujie-devsecops`命名空间。此阶段严格遵循以下顺序：首先配置集群 → 分发节点 → 配置代理工具 → 配置 IAM 权限 → 配置 aws-auth 映射 → 配置 RBAC → 部署。

> 提醒：在点击“立即构建”之前，**请先推送**您的清单文件，以便流水线拥有最新文件。

------

### **pipeline片段**



```
pipeline {
  agent { label 'docker-maven-trivy' }
  tools {
    maven 'maven3'
  }
  environment {
    SONAR_IP = '3.148.193.21'
    ECR_REGISTRY = '253685958295.dkr.ecr.us-east-2.amazonaws.com'
    IMAGE_REPO = "${ECR_REGISTRY}/zhoujie-devsecops-demo"
  }
  stages {
    stage('Trivy FS Scan') {
      steps {
        sh 'trivy fs --exit-code 1 --severity HIGH,CRITICAL .'
      }
    }
    stage('Build & Sonar') {
      steps {
        withCredentials([string(credentialsId: 'sonarqube-token', variable: 'SONAR_TOKEN')]) {
          sh 'mvn clean verify sonar:sonar \
            -Dsonar.projectKey=zhoujie-devsecops-demo \
            -Dsonar.host.url="http://${SONAR_IP}:9000" \
            -Dsonar.token="${SONAR_TOKEN}" \
            -Dsonar.qualitygate.wait=true'
        }
      }
    }
    stage('ECR Login') {
      steps {
        sh 'aws ecr get-login-password --region us-east-2 | docker login --username AWS --password-stdin $ECR_REGISTRY'
      }
    }
    stage('Build Image') {
      steps {
        sh 'export DOCKER_BUILDKIT=0 && docker build --platform linux/amd64 -t "$IMAGE_REPO:$BUILD_NUMBER" -t "$IMAGE_REPO:latest" .'
      }
    }
    stage('Trivy Image Scan') {
      steps {
        sh 'trivy image --exit-code 1 --severity HIGH,CRITICAL "$IMAGE_REPO:$BUILD_NUMBER"'
      }
    }
    stage('Push to ECR') {
      steps {
        sh 'docker push "$IMAGE_REPO:$BUILD_NUMBER"'
        sh 'docker push "$IMAGE_REPO:latest"'
      }
    }
    stage('Update Deployment') {
      steps {
        sh 'sed -i "s|image:.*|image: $IMAGE_REPO:$BUILD_NUMBER|g" deploy-svc.yaml'
      }
    }
    stage('Deploy to Kubernetes') {
      steps {
        sh '''#!/bin/bash -l
aws eks update-kubeconfig \
  --region us-east-2 \
  --name zhoujie-devsecops-eks \
  --kubeconfig /home/jenkins/.kube/config

kubectl create ns zhoujie-devsecops
kubectl apply -f deploy-svc.yaml

kubectl rollout status -n zhoujie-devsecops deployment/zhoujie-devsecops-demo --timeout=60s || {
  kubectl rollout undo -n zhoujie-devsecops deployment/zhoujie-devsecops-demo || true
  exit 1
}
'''
      }
    }
  }
}
```

注意：网页无法访问是因为没有开放入站的31000端口，打开即可

------

### **本阶段的作用（概述）**



- 确保 Jenkins 代理可以通过更新后的 kubeconfig 与 EKS 集群通信。
- 创建演示命名空间并应用部署+服务清单。
- 等待部署完成；如果失败，则撤销部署并使该阶段失败。
- 强制执行最小权限原则：我们只授予 Jenkins 代理所需的 RBAC 动词（部署、副本集、Pod、服务、命名空间）。

------

### **预备步骤（必须先做的事情——顺序）**



1. 使用`eksctl`提供的配置**创建 EKS 集群。**
2. 使用以下**命令验证节点可用区分布**`kubectl get nodes --show-labels`。
3. **安装`kubectl`到 jenkins-agent 上**（版本应与 EKS 控制平面匹配）。
4. **将内联 EKS DescribeCluster 策略附加**到`jenkins-agent-role`。
5. **编辑`aws-auth`configmap**，将 IAM 角色映射到 Kubernetes 用户名。
6. **创建集群角色和集群角色绑定，**并授予有限的权限。
7. **提交并推送** `deploy-svc.yaml`RBAC 清单到仓库。

------

### **创建 EKS 集群——使用单行注释进行配置**



为了更清晰地表达，请创建`eks-config.yaml`并添加以下单行注释：

![](./images/1757a036-54fa-4b9e-bf98-2be671ae16ea.png)

AWS托管框里的部分不用我们管理，由Amazon帮我们管理，我们只需要管理客户自管框里的东西

#### 安装所需工具

### **1) 安装 AWS CLI v2（系统范围）**



```bash
# 下载 aws cli v2
curl "https://awscli.amazonaws.com/awscli-exe-linux-x86_64.zip" -o awscliv2.zip

# 安装 unzip
sudo apt install unzip

# 解压
unzip awscliv2.zip

# 系统范围安装，这样所有用户（包括 jenkins）都可以访问
sudo ./aws/install
```

### 安装 eksctl

```bash
curl --silent --location \
"https://github.com/weaveworks/eksctl/releases/latest/download/eksctl_$(uname -s)_amd64.tar.gz" \
| tar xz -C /tmp

sudo mv /tmp/eksctl /usr/local/bin
```

验证安装：

- [AWS CLI](https://docs.aws.amazon.com/cli/latest/userguide/getting-started-install.html)
- [eksctl](https://eksctl.io/installation/)

```yaml
apiVersion: eksctl.io/v1alpha5            # eksctl 集群配置 API 版本
kind: ClusterConfig                      # eksctl 的资源类型
metadata:                                # 集群元数据块
  name: zhoujie-devsecops-eks               # EKS 集群名称
  region: us-east-2                     # 集群所在的 AWS 区域
  version: "1.32"                        # Kubernetes 控制平面版本
  tags:                                  # 用于标识资源的标签
    owner: zhoujie               # 标签：所有者
    environment: training                 # 标签：环境
    project: zhoujie-devsecops-demo         # 标签：项目
    purpose: test                # 标签：用途
availabilityZones:                        # 用于节点放置的可用区
  - us-east-2a                           # 可用区 1
  - us-east-2b                           # 可用区 2
iam:                                      # IAM/OIDC 设置
  withOIDC: true                         # 为 IRSA 启用 IAM OIDC 提供者
managedNodeGroups:                        # 定义托管节点组
  - name: zhoujie-ng-public                  # 节点组名称
    instanceType: t3.small                # 工作节点的 EC2 实例类型
    minSize: 1                            # 节点组中的最小节点数
    maxSize: 2                            # 节点组中的最大节点数
    desiredCapacity: 1                    # 创建时的期望节点数量
    volumeSize: 20                        # 根磁盘大小（GiB）
    privateNetworking: false              # 节点获取公网网络配置
    ssh:                                  # SSH 配置
      allow: true                         # 允许 SSH 访问节点
      publicKeyName: zhoujie-aws-Ohio       # 用于 SSH 的 EC2 密钥对名称
    iam:                                  # 每个节点组的 IAM 设置
      withAddonPolicies:                  # 启用插件策略
        autoScaler: true                  # 允许使用集群自动伸缩器
        ebs: true                         # 允许使用 EBS 卷
        albIngress: true                  # 允许使用 ALB Ingress 插件
        externalDNS: true                 # 允许使用 ExternalDNS 插件
    labels:                               # 用于调度的节点标签
      owner: zhoujie            # 节点标签：所有者
      app: devsecops-demo                 # 节点标签：应用
    tags:                                 # 节点级别的标签
      node-type: public-worker            # 自定义标签：节点类型
      managed-by: eksctl                  # 自定义标签：由 eksctl 管理
```



创建集群：

```bash
eksctl create cluster -f eks-config.yaml
```



验证节点可用区分布：

```bash
kubectl get nodes --show-labels | grep topology.kubernetes.io/zone
```



------

### **在 Jenkins Agent 上安装 kubectl**



```bash
# 下载适用于 EKS 版本 1.32 的 kubectl 二进制文件
curl -LO https://dl.k8s.io/release/v1.32.0/bin/linux/amd64/kubectl

# 使其成为可执行文件
chmod +x kubectl

# 将其移动到 /usr/local/bin 目录，以便所有用户都能在 PATH 中找到它
sudo mv kubectl /usr/local/bin/
```



核实：

```bash
kubectl version --client
sudo -u jenkins kubectl version --client
```



------

### **修改`jenkins-agent-role`以允许 kubeconfig 更新**



为集群附加一个`jenkins-agent-role`允许的内联策略：`eks:DescribeCluster`

```bash
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": [
        "eks:DescribeCluster"
      ],
      "Resource": "arn:aws:eks:us-east-2:253685958295:cluster/zhoujie-devsecops-eks"
    }
  ]
}
```

Resource指的是EKS集群的集群ARN打开EKS的控制面板可以看见

通过 IAM 控制台应用：**角色 → jenkins-agent-role →添加权限--> 创建内联策略--->服务选择ec2，选择json，粘贴上面的内容，点击下一步，名字写temp-eksnode-access,点击创建策略**。

------

### **为什么要编辑`aws-auth`以及如何编辑？**



aws **-auth ConfigMap**控制 AWS IAM 角色如何映射到 Kubernetes 用户和组。EKS 使用此映射来确定**谁可以**对集群进行身份验证，然后 RBAC 决定**他们可以执行哪些操作**。

在添加我们的 Jenkins 角色之前，让我们先了解一下由以下方式自动创建的**现有**`eksctl`ConfigMap ：

```yaml
apiVersion: v1
data:
  mapRoles: |
    - rolearn: arn:aws:iam::386275436648:role/eksctl-zhoujie-devsecops-eks-nodegrou-NodeInstanceRole-J3Szf4uMWv1R
      groups:
      - system:bootstrappers
      - system:nodes
      username: system:node:{{EC2PrivateDNSName}}
kind: ConfigMap
metadata:
  name: aws-auth
  namespace: kube-system
```



------

### **了解现有的 aws-auth 条目**



此条目表示工作节点使用的**节点实例角色。**

- `system:bootstrappers` 允许节点在引导过程中安全地**加入集群**。
- `system:nodes` 授予节点读取/写入节点对象、报告状态、挂载卷、向调度器注册等权限。这是一个**权限很高的组**——节点需要它来进行集群操作，但*绝不*应该将人分配到这个组。
- `username: system:node:{{EC2PrivateDNSName}}` 每个工作节点都根据其私有 DNS 名称表示为一个 Kubernetes 用户。

这些权限**仅适用于 Kubernetes 节点**，不适用于 CI/CD 系统或用户。授予 Jenkins 这些权限将使其能够：

✔ 像节点一样运行 ✔ 更新自身状态 ✔ 通过节点权限读写密钥 ✔ 跨命名空间执行节点级操作

这违反了最小特权原则，而且很**危险**。

------

### **为什么我们不将 Jenkins 添加到 system:masters 或节点组**



- `system:masters`→**完全集群管理员**，不受限制的权限（管理节点、密钥、CRD 等所有内容）。
- `system:nodes`或者`system:bootstrappers`→**特权节点身份**，仅适用于 kubelet。

授予 Jenkins 这些组权限实际上会赋予它与集群管理员或节点身份相同的权限，这是不必要的，也是不安全的。

流水线部署**绝不能**以集群管理员权限运行。

------

### **为什么我们只使用`system:authenticated`**



我们将 Jenkins IAM 的角色映射到：

```yaml
groups:
  - system:authenticated
```



该组指的是：

- “您是已知的、经过身份验证的用户。”
- 但默认情况下您**没有**任何权限。
- RBAC 将决定 Jenkins 究竟可以做什么。

这强制执行**严格的最小权限原则**：

- 身份验证 → 通过 aws-auth
- 授权 → 稍后通过我们创建的 ClusterRole + ClusterRoleBinding 授予。

这种隔离方式保证了集群的安全性和可预测性。

------

### **将 Jenkins IAM 角色添加到 aws-auth**



现在我们已经了解了现有的映射关系，我们补充以下内容：

```yaml
mapRoles: |
  - rolearn: arn:aws:iam::253685958295:role/jenkins-agent-role
    username: jenkins-agent-role
    groups:
      - system:authenticated
```



（从管理员计算机）编辑 ConfigMap：

```bash
kubectl -n kube-system edit configmap aws-auth
```



------

### **验证映射**



```bash
kubectl describe configmap aws-auth -n kube-system
```



现在你应该看到**两条记录**：

1. NodeInstanceRole（引导程序 + 节点）
2. jenkins-agent-role（系统：已认证）

------

### **授予精确的基于角色的访问控制（最小权限）**

### 一句话总结

- **Role**：作用在**某个命名空间内**（比如只能访问 `default` 或 `kube-system` 空间里的资源）。
- **ClusterRole**：作用在**整个集群级别**（比如可以访问所有命名空间的资源，或管理节点、存储类等集群资源）。

## 详细对比

| 维度               | Role                                                         | ClusterRole                                                  |
| :----------------- | :----------------------------------------------------------- | :----------------------------------------------------------- |
| **作用范围**       | 单个 Namespace（命名空间）                                   | 整个集群                                                     |
| **适用的资源类型** | 命名空间内的资源：Pod、Service、Deployment、ConfigMap、Secret 等 | **集群级别资源**：Node、Namespace、PersistentVolume、StorageClass、CustomResourceDefinition（CRD）等  **以及所有命名空间内的资源**（通过指定 Namespace） |
| **典型使用场景**   | 让某个用户只能在 `dev` 空间里操作 Pod                        | 让某个用户能查看所有空间的 Pod，或管理集群的节点             |
| **能否跨命名空间** | ❌ 不能                                                       | ✅ 能（如果规则里允许访问某类资源，它默认可以访问所有空间的这类资源） |

创建`clusterrole`后， （映射的用户`clusterrolebinding`名`jenkins-agent-role`）可以管理部署、副本集、服务、Pod 和命名空间——仅此而已。

`clusterrole-binding.yaml`（两份清单合并后）：

```yaml
apiVersion: rbac.authorization.k8s.io/v1
kind: ClusterRole
metadata:
  name: jenkins-deployer-role
rules:
  - apiGroups: ["apps"]
    resources: ["deployments", "replicasets"]
    verbs: ["get", "list", "watch", "create", "update", "patch", "delete"]
  - apiGroups: [""]
    resources: ["pods"]
    verbs: ["get", "list", "watch", "delete"]
  - apiGroups: [""]
    resources: ["services"]
    verbs: ["get", "list", "watch", "create", "update", "patch", "delete"]
  - apiGroups: [""]
    resources: ["namespaces"]
    verbs: ["get", "list", "watch", "create", "delete", "patch", "update"]
---
apiVersion: rbac.authorization.k8s.io/v1
kind: ClusterRoleBinding
metadata:
  name: jenkins-deployer-binding
subjects:
  - kind: User
    name: jenkins-agent-role   # must match username in aws-auth
    apiGroup: rbac.authorization.k8s.io
roleRef:
  kind: ClusterRole
  name: jenkins-deployer-role
  apiGroup: rbac.authorization.k8s.io
```



应用（从集群管理员机器，在我们创建EKS集群的那台EC2主机上执行下面这条命令）：

```bash
kubectl apply -f clusterrole-binding.yaml
```



**验证：**尝试从 jenkins-agent 执行 kubectl 命令（pods/deployments/services/namespaces 应该可以正常工作；nodes list 应该会失败）。

------

### **pipeline逐行解释**



这是您要求逐行解释的代码块：

```
#!/bin/bash -l
aws eks update-kubeconfig \
  --region us-east-2 \
  --name zhoujie-devsecops-eks \
  --kubeconfig /home/jenkins/.kube/config

kubectl create ns zhoujie-devsecops
kubectl apply -f deploy-svc.yaml

kubectl rollout status -n zhoujie-devsecops deployment/zhoujie-devsecops-demo --timeout=60s || {
  kubectl rollout undo -n zhoujie-devsecops deployment/zhoujie-devsecops-demo || true
  exit 1
}
```

当你运行多条命令时，并且你希望只调用shell，你可以使用这个方法，sh ''' ....'''

1. `aws eks update-kubeconfig \` 开始执行`aws`CLI 命令，为指定的 EKS 集群填充 kubeconfig 文件。这样就可以`kubectl`使用基于 IAM 的身份验证与集群通信。
2. `  --region ap-south-1 \` 指定 EKS 集群所在的 AWS 区域。
3. `  --name cwvj-devsecops-eks \` 要生成/更新 kubeconfig 的 EKS 集群的名称。
4. `  --kubeconfig /home/jenkins/.kube/config` 将 kubeconfig 写入 Jenkins 用户的 kubeconfig 路径，以便后续`kubectl`以 Jenkins 身份运行的命令将使用它。
5. `kubectl create ns cwvj-devsecops` 如果命名空间`cwvj-devsecops`不存在，则创建它。`kubectl apply`如果包含命名空间清单，也会创建它，但这种显式创建方式更简单且幂等。
6. `kubectl apply -f deploy-svc.yaml` 将部署和服务清单应用到当前上下文（kubeconfig）中的集群。这将创建/更新应用程序资源。
7. `kubectl rollout status -n cwvj-devsecops deployment/cwvj-devsecops-demo --timeout=60s` 最多等待 60 秒，直至部署完成。当部署达到预期状态时，返回成功。
8. `|| {` 如果上一个`rollout status`命令失败（超时或 pod 不健康），则执行大括号内的命令作为故障恢复块。
9. `  kubectl rollout undo -n cwvj-devsecops deployment/cwvj-devsecops-demo || true` 尝试回滚到之前的部署版本。这样`|| true`即使回滚失败，脚本也能继续执行下一行（以便之后可以返回非零代码退出）。
10. `  exit 1` 脚本以非零退出代码退出，以便 Jenkins 将该阶段标记为失败。这可以防止将错误的部署视为成功。
11. `}` 关闭故障处理块。

------

### **验证步骤**



管道运行后（或手动运行后）：

- `kubectl get ns`→ 确认`zhoujie-devsecops`存在。
- `kubectl get deploy -n zhoujie-devsecops`→ 检查部署是否存在。
- `kubectl rollout status -n zhoujie-devsecops deployment/cwvj-devsecops-demo`→ 确认稳定上线。
- `kubectl get pods -n zhoujie-devsecops -o wide`→ 验证 Pod 在节点/可用区之间的分布情况。
- 测试服务访问权限：如果允许`kubectl get svc zhoujie-devsecops-svc -n zhoujie-devsecops`则点击`NodePort`。

------

### **注意事项、陷阱和最佳实践**



- 确保 Jenkins 代理已安装 AWS CLI，`kubectl`并拥有正确的 PATH 环境变量。
- `aws eks update-kubeconfig`需要`eks:DescribeCluster`获得该角色的权限。
- `aws-auth`映射提供身份验证；RBAC 集群角色绑定提供授权。尽量减少绑定数量。
- 滚动更新超时可能需要针对冷启动或镜像拉取进行调整。
- 对于生产环境，请使用 IRSA、服务帐户或密封为密钥的 kubeconfig——避免使用广泛的权限。
- 始终`git push`在触发管道之前进行清单编制，以确保管道使用最新文件。

------

## **Jenkins Pipeline 中的后续操作**



在 Jenkinsfile 的末尾，我们添加了一个代码块，用于定义 Jenkins**在整个流水线完成后**`post`必须执行的操作，无论哪个阶段成功或失败。

此代码块位于**代码块****之外`stages {}`**，因为它应用于*整个流水线*，而不是任何单个阶段。如果将其放在代码块内`stages`，Jenkins 将抛出错误。

------

### **pipeline片段**



```
pipeline {
  agent { label 'docker-maven-trivy' }
  tools {
    maven 'maven3'
  }
  environment {
    SONAR_IP = '3.148.193.21'
    ECR_REGISTRY = '253685958295.dkr.ecr.us-east-2.amazonaws.com'
    IMAGE_REPO = "${ECR_REGISTRY}/zhoujie-devsecops-demo"
  }
  stages {
    stage('Trivy FS Scan') {
      steps {
        sh 'trivy fs --exit-code 1 --severity HIGH,CRITICAL .'
      }
    }
    stage('Build & Sonar') {
      steps {
        withCredentials([string(credentialsId: 'sonarqube-token', variable: 'SONAR_TOKEN')]) {
          sh 'mvn clean verify sonar:sonar \
            -Dsonar.projectKey=zhoujie-devsecops-demo \
            -Dsonar.host.url="http://${SONAR_IP}:9000" \
            -Dsonar.token="${SONAR_TOKEN}" \
            -Dsonar.qualitygate.wait=true'
        }
      }
    }
    stage('ECR Login') {
      steps {
        sh 'aws ecr get-login-password --region us-east-2 | docker login --username AWS --password-stdin $ECR_REGISTRY'
      }
    }
    stage('Build Image') {
      steps {
        sh 'export DOCKER_BUILDKIT=0 && docker build --platform linux/amd64 -t "$IMAGE_REPO:$BUILD_NUMBER" -t "$IMAGE_REPO:latest" .'
      }
    }
    stage('Trivy Image Scan') {
      steps {
        sh 'trivy image --exit-code 1 --severity HIGH,CRITICAL "$IMAGE_REPO:$BUILD_NUMBER"'
      }
    }
    stage('Push to ECR') {
      steps {
        sh 'docker push "$IMAGE_REPO:$BUILD_NUMBER"'
        sh 'docker push "$IMAGE_REPO:latest"'
      }
    }
    stage('Update Deployment') {
      steps {
        sh 'sed -i "s|image:.*|image: $IMAGE_REPO:$BUILD_NUMBER|g" deploy-svc.yaml'
      }
    }
    stage('Deploy to Kubernetes') {
      steps {
        sh '''#!/bin/bash -l
aws eks update-kubeconfig \
  --region us-east-2 \
  --name zhoujie-devsecops-eks \
  --kubeconfig /home/jenkins/.kube/config

kubectl create ns zhoujie-devsecops
kubectl apply -f deploy-svc.yaml

kubectl rollout status -n zhoujie-devsecops deployment/zhoujie-devsecops-demo --timeout=60s || {
  kubectl rollout undo -n zhoujie-devsecops deployment/zhoujie-devsecops-demo || true
  exit 1
}
'''
      }
    }
  }
  post {
    success { 
      echo "Build ${env.BUILD_NUMBER} succeeded" 
    }
    failure { 
      echo "Build ${env.BUILD_NUMBER} failed" 
    }
    always { 
      echo "Build ${env.BUILD_NUMBER} finished" 
    }
  }
}
```



------

### **解释**



- `post { ... }` 全局部分定义了 Jenkins 在流水线完成后运行的操作——无论阶段内发生了什么。
- `success { ... }` 仅当**整个流水线**成功运行时才会运行。常见用途：发送部署通知（如发送成功邮件）、推送构建产物、将构建标记为稳定版本。
- `failure { ... }`**当任何阶段失败** 时运行。生产环境用途：发送警报、通知值班团队、创建 Jira 工单、记录失败指标。
- `always { ... }`即使流水线中止、不稳定或失败，也会**每次都** 运行。生产环境用途：工作区清理、日志归档、关闭外部会话、Slack 通知、清理 Docker 镜像、删除临时文件。

------

### **为什么这些属于外部`stages`**



该`stages {}`代码块定义了流水线步骤的顺序。该`post {}`代码块定义了整个*流水线运行的***全局生命周期操作**。将其放在内部会导致 Jenkins 将其视为另一个阶段，这是无效的。`stages`

正确结构：

```
pipeline {
  agent any

  stages {
    stage('Build') { ... }
    stage('Deploy') { ... }
  }

  post {
    success { ... }
    failure { ... }
    always  { ... }
  }
}
```

------

## Git 推送触发**流水线**



**推荐方案：多分支流水线 + GitHub Webhook**

1. 创建指向您的代码仓库的 Jenkins 多分支流水线作业。
	- Jenkins 将自动扫描分支并读取`Jenkinsfile`。
2. 在 Jenkins 凭据中添加 GitHub 的 Jenkins 凭据（用户名/令牌）。
	- 请使用机器令牌，不要使用个人密码。
3. 在仓库 → 设置 → Webhooks 中添加 GitHub webhook。
	- 有效载荷 URL：`https://<JENKINS_HOST>/github-webhook/`
	- 内容类型：`application/json`
	- 密钥：添加一个强密钥并将其存储在 Jenkins 作业配置中（可选但推荐）。
	- 事件：选择`Push`（以及`Pull request`是否需要 PR 构建）。
4. 在多分支设置中配置作业分支过滤器和构建策略。
	- 如果需要，可以排除`dependabot`分支`docs`。
5. 从 GitHub UI 测试 webhook → 发送`Push`测试有效负载。
	- 检查 Jenkins 作业索引和构建日志以确认触发条件。

**替代方案（简单）：投票供应链管理**

- 配置`Poll SCM`cron 作业（例如`H/5 * * * *`），作为 webhook 被阻止时的备用方案。

**安全/生产提示**

- 在 Jenkins 中使用 webhook 密钥并启用密钥验证。
- 使用细粒度的 GitHub 令牌和最小权限原则。
- 优先选择多分支模式，以实现清晰的分支/PR隔离和自动发现。

------

### **按实际应用结果分类：**



**成功：**

- 通知：Slack、Teams、电子邮件，用于通知构建成功。
- Git 运维：构建成功后，给提交添加标签或对 PR 进行评论。
- 报告：发布 JUnit、代码覆盖率和安全摘要。

**失败：**

- 通知：立即提醒开发人员或值班人员。
- 回滚：触发回滚管道或将部署标记为失败。
- 调试数据：上传日志或诊断包以进行故障排查。

**总是：**

- 清理：删除临时文件、旧工件、Docker 镜像。
- 可观测性：将指标/日志推送至 CloudWatch、Datadog、Prometheus、ELK。
- 内部管理：归档资料并关闭正在进行的会话。

------
## **如何改进这条生产线（生产效率提升）**

您构建的流水线特意设计得非常简单，以便您可以清晰地了解每个环节。在实际的 DevSecOps 环境中，团队会扩展此工作流程，引入更强大的自动化、GitOps、更优的部署策略和企业级安全措施。以下是*最重要的升级*。
**1. 使用 Kubernetes Ingress/Gateway API 代替 NodePort（强烈推荐）**
### 2. 多分支流水线（对真正的 CI/CD 至关重要）**
**3. 使用 DNS 名称而不是 IP 地址（生产环境中始终采用）**
**4. 使用图像摘要而不是标签（企业最佳实践）**



