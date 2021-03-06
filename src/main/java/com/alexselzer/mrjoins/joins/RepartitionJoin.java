package com.alexselzer.mrjoins.joins;

import com.alexselzer.mrjoins.Join;
import com.alexselzer.mrjoins.JoinConfig;
import com.alexselzer.mrjoins.JoinStats;
import com.alexselzer.mrjoins.JoinTuple;
import com.alexselzer.mrjoins.utils.JobUtils;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.io.WritableComparator;
import org.apache.hadoop.mapreduce.*;
import org.apache.hadoop.mapreduce.lib.input.MultipleInputs;
import org.apache.hadoop.mapreduce.lib.input.TextInputFormat;
import org.apache.hadoop.mapreduce.lib.output.FileOutputFormat;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class RepartitionJoin implements Join {
    private Job job;
    private JoinStats stats = new JoinStats();

    public static class HashJoinLeftMapper extends Mapper<Object, Text, JoinTuple, JoinTuple>{
        public void map(Object o, Text text, Context context) throws IOException, InterruptedException {
            //System.out.printf("Mapping %s (left)\n", text);
            String joinAttr = text.toString().split(",")[context.getConfiguration().getInt("index1", 0)];
            context.write(new JoinTuple(0, new Text(joinAttr)), new JoinTuple(0, text));
            //System.out.printf("Wrote %s\n",  new JoinTuple(0, text));
        }
    }

    public static class HashJoinRightMapper extends Mapper<Object, Text, JoinTuple, JoinTuple> {
        public void map(Object o, Text text, Context context) throws IOException, InterruptedException {
            //System.out.printf("Mapping %s (right)\n", text);
            String joinAttr = text.toString().split(",")[context.getConfiguration().getInt("index2", 0)];
            context.write(new JoinTuple(1, new Text(joinAttr)), new JoinTuple(1, text));
            //System.out.printf("Wrote %s\n",  new JoinTuple(1, text));
        }
    }

    public static class HashJoinReducer extends Reducer<JoinTuple, JoinTuple, Text, Text> {
        public void reduce(JoinTuple key, Iterable<JoinTuple> values, Context context) throws IOException, InterruptedException {
            // The values have to be cached since the iterator can only be iterated through once
            List<JoinTuple> tuples = new ArrayList<>();
            for (JoinTuple t : values) {
                // https://cornercases.wordpress.com/2011/08/18/hadoop-object-reuse-pitfall-all-my-reducer-values-are-the-same/
                // Clone the JoinTuple object and its Writables because otherwise all values will be the same...
                tuples.add(new JoinTuple(t));
                //System.out.printf("Got %s\n", t);
            }

            //System.out.printf("Key: %s, Tuples: %s", key, tuples);
            for (JoinTuple t1 : tuples) {
                for (JoinTuple t2: tuples) {
                    //System.out.printf("Reducer: processing %s, %s\n", t1, t2);
                    if (t1.getTableIndex().get() != t2.getTableIndex().get() && t1.getTableIndex().get() == 0) {
                        //System.out.printf("Reducer: %s, %s, join attrs: %s, %s\n", t1, t2, joinAttr1, joinAttr2);

                        context.write(key.getTuple(), new Text(t1.getTuple().toString() + "," + t2.getTuple().toString()));
                    }
                }
            }
        }
    }

    public static class HashJoinOptimizedReducer extends Reducer<JoinTuple, JoinTuple, Text, Text> {
        public void reduce(JoinTuple key, Iterable<JoinTuple> values, Context context) throws IOException, InterruptedException {
            // The tuples from the first table will come first in the list. They are saved to a list to
            // reduce the memory consumption
            List<String> firstTableTuples = new ArrayList<>();

            for (JoinTuple t : values) {
                if (t.getTableIndex().get() == 0) {
                    firstTableTuples.add(t.getTuple().toString());
                }
                else {
                    for (String t1Tuple : firstTableTuples) {
                        context.write(key.getTuple(), new Text(t1Tuple + "," + t.getTuple().toString()));
                    }
                }
            }
        }
    }

    public static class JoinPartitioner extends Partitioner<JoinTuple, JoinTuple> {
        @Override
        public int getPartition(JoinTuple key, JoinTuple value, int numPartitions) {
            return (key.getTuple().hashCode() & Integer.MAX_VALUE) % numPartitions;
        }
    }

    public static class GroupingComparator extends WritableComparator {
        protected GroupingComparator() {
            super(JoinTuple.class);
        }

        public int compare(byte[] b1, int s1, int l1, byte[] b2, int s2, int l2) {
            // Ignore the first 4 bytes when comparing - 32 bit integer
            return compareBytes(b1, s1 + 4, l1 - 4, b2, s2 + 4, l2 - 4);
        }
    }

    @Override
    public void init(JoinConfig config, String name) throws IOException {
        Configuration jobConf = new Configuration();
        jobConf.setInt("index1", config.getIndices()[0]);
        jobConf.setInt("index2", config.getIndices()[1]);

        job = Job.getInstance(jobConf, name == null ? "repartition-join" : name);

        job.setJarByClass(RepartitionJoin.class);

        MultipleInputs.addInputPath(job, config.getInputs()[0], TextInputFormat.class, HashJoinLeftMapper.class);
        MultipleInputs.addInputPath(job, config.getInputs()[1], TextInputFormat.class, HashJoinRightMapper.class);
        FileOutputFormat.setOutputPath(job, config.getOutput());

        job.setPartitionerClass(JoinPartitioner.class);
        job.setGroupingComparatorClass(GroupingComparator.class);

        job.setReducerClass(HashJoinOptimizedReducer.class);

        job.setMapOutputKeyClass(JoinTuple.class);
        job.setMapOutputValueClass(JoinTuple.class);

        job.setOutputKeyClass(Text.class);
        job.setOutputValueClass(Text.class);

        job.setNumReduceTasks(config.getNumReducers());
    }

    public void init(JoinConfig config, String name, boolean extractKeys) throws IOException {
        init(config, name, false);
    }

    @Override
    public boolean run(boolean verbose) throws InterruptedException, IOException, ClassNotFoundException {
        long time = JobUtils.time(job, verbose);

        stats.setJobTimes(new long[] {time});
        stats.setCounters(job.getCounters());
        stats.setMapTasks(job.getTaskReports(TaskType.MAP));
        stats.setReduceTasks(job.getTaskReports(TaskType.REDUCE));

        return !(time == 0);
    }

    @Override
    public JoinStats getJoinStats() {
        return stats;
    }


    public static void main(String[] args) throws IOException, ClassNotFoundException, InterruptedException {
        if (args.length != 5) {
            System.err.println("Usage: RepartitionJoin.jar [input1] [index1] [input2] [index2] [output]");
            System.exit(1);
        }

        Path input1 = new Path(args[0]);
        Path input2 = new Path(args[2]);
        Path output = new Path(args[4]);

        int index1 = Integer.parseInt(args[1]);
        int index2 = Integer.parseInt(args[3]);

        Path[] inputs = {input1, input2};
        Integer[] indices = {index1, index2};

        JoinConfig config = new JoinConfig(inputs, indices, output, 1);

        Join join = new RepartitionJoin();
        join.init(config, "Repartition Join");

        join.run(true);
    }
}
