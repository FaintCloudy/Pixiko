package cn.szu.bot.web;

import cn.szu.bot.Bot;
import cn.szu.bot.Settings;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.HashMap;
import java.util.Map;

/**
 * 网页控制台的 Spring Boot 入口。
 *
 * <p>机器人本体（QQ / SD / 队列 / 词库）不依赖任何框架，这里只把<b>网页层</b>交给 Spring Boot：
 * Tomcat 提供 HTTP、控制器提供接口与页面、过滤器做访问令牌校验。机器人自己的 {@link Settings} 与
 * {@link Bot} 实例在上下文刷新前注册成单例，控制器按类型注入，和命令行版本共用同一套业务对象。
 *
 * <p>只扫描 {@code cn.szu.bot.web}，不会把机器人其它包当成组件扫进来。
 */
@SpringBootApplication(scanBasePackages = "cn.szu.bot.web")
public class WebUiApplication {

    /**
     * 起一个内嵌 Tomcat 的网页控制台。
     *
     * @param host 监听地址（config.json 的 webui.host）
     * @param port 监听端口（config.json 的 webui.port，0 表示随机端口）
     */
    public static ConfigurableApplicationContext start(Settings settings, Bot bot, String host, int port) {
        SpringApplication application = new SpringApplication(WebUiApplication.class);
        application.setWebApplicationType(WebApplicationType.SERVLET);
        application.setBannerMode(org.springframework.boot.Banner.Mode.OFF);
        // 机器人对象是外面创建好的：注册成单例，控制器直接按类型注入。
        application.addInitializers(context -> {
            context.getBeanFactory().registerSingleton("pixikoSettings", settings);
            context.getBeanFactory().registerSingleton("pixikoBot", bot);
        });
        Map<String, Object> properties = new HashMap<>();
        properties.put("server.address", host);
        properties.put("server.port", port);
        // Tomcat 默认会往 stdout 打一堆启动行：机器人自己的日志已经写进 logs/，这里只留警告。
        properties.put("logging.level.root", "warn");
        properties.put("logging.level.org.apache", "warn");
        properties.put("server.tomcat.accesslog.enabled", "false");
        properties.put("spring.mvc.log-request-details", "false");
        properties.put("spring.main.banner-mode", "off");
        application.setDefaultProperties(properties);
        return application.run();
    }
}
