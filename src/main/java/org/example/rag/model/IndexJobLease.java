package org.example.rag.model;

/** 每次领取都产生不同令牌，过期工作线程无法发布新领取者的任务。 */
public record IndexJobLease(String id, String owner) { }
