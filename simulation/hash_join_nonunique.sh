#!/bin/sh

rm -r output
hadoop jar ../target/mapreduce-join-comparison-1.0-SNAPSHOT.jar joins.com.alexselzer.mrjoins.joins.HashJoin \
simple_tables/t1_nonunique.csv 0 \
simple_tables/t2.csv 0 output
