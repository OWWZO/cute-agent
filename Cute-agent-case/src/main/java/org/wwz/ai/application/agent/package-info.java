/**
 * Agent 应用编排层。
 * 负责 dispatch / execute / query / dataquery / run / stream / channel / file / image 等
 * 跨领域编排，不承载底层协议细节。
 * Trigger 入口必须优先依赖本层 seam，而不是直接依赖 domain/service 根接口。
 */
package org.wwz.ai.application.agent;
