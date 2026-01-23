package com.signal.Signal.config;

import com.signal.Signal.websocket.SignalSocketHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final SignalSocketHandler signalSocketHandler;

    public WebSocketConfig(SignalSocketHandler signalSocketHandler) {
        this.signalSocketHandler = signalSocketHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        // NOTE: Use "wss://" in frontend, but mapping here stays "/ws-signal"
        registry.addHandler(signalSocketHandler, "/ws-signal")
                .setAllowedOrigins("*");
    }


    @Bean
    public ServletServerContainerFactoryBean createWebSocketContainer() {
        ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();
        container.setMaxTextMessageBufferSize(1024 * 1024);

        container.setMaxBinaryMessageBufferSize(1024 * 1024);
        return container;
    }
}