package islab.keyplayer;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaPairRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.broadcast.Broadcast;

import scala.Tuple2;

public class KeyPlayer {
	
	public static void main(String[] args) {
		
		SparkConf conf = new SparkConf().setAppName("KeyPlayerSpark").setMaster("spark://lar01:7077");
		JavaSparkContext sc = new JavaSparkContext(conf);
		
		String sInputPath = "./graph_data/graph_oneline.json";
		if (args[0].equals("-in")) {
			sInputPath = args[1];

			// TODO Auto-generated method stub
			long lStart = System.currentTimeMillis();
			Graph g = new Graph();

			Data data = new Data();
			g = data.createGraphFromJSONFile(sInputPath);
			List<Vertex> vertices = g.getVertices();
			int iVertexNum = vertices.size();
			List<Edge> edges = g.getEdges();
			
			Utils u = new Utils(sc);
			
			System.out.println("" + u.GraphToString(vertices, edges));
			
			long lStart2 = System.currentTimeMillis();
			
			if (args[2].equals("-b1")) {
				lStart2 = System.currentTimeMillis();
				System.out.println("Sức ảnh hưởng gián tiếp của đỉnh " + args[3] + " lên đỉnh " + args[4] + " là: "
						+ u.IndirectInfluenceOfVertexOnOtherVertex(vertices, edges, args[3], args[4]));
			}

			if (args[2].equals("-b2")) {
				lStart2 = System.currentTimeMillis();
				//Cách 1
				if (args[3].equals("c1")) {
					JavaPairRDD<String, BigDecimal> all = u.getAllInfluenceOfVertices(vertices, edges);
					all.cache();

					System.out.println("Sức ảnh hưởng của tất cả các đỉnh:");
					List<Tuple2<String, BigDecimal>> listAll = all.collect();
					for (Tuple2<String, BigDecimal> tuple : listAll) {
						System.out.println("[ " + tuple._1 + " : " + tuple._2.toString() + " ]");
					}

					System.out.println("Key Player là: ");
					Tuple2<String, BigDecimal> kp = all.first();
					System.out.println(kp._1 + ": " + kp._2.toString());
				}
				//Cách 2
				if (args[3].equals("c2")) {
					List<Segment> listOneSegment = u.getSegmentFromEdges(vertices, edges);
					MongoDBSpark mongospark = new MongoDBSpark();
					mongospark.insertSegmentToMongoDB(listOneSegment);
					int iSegmentLevel = 2;
					List<Segment> listSegment = listOneSegment;
					Broadcast<List<Segment>> bcOneSegment = sc.broadcast(listOneSegment);
					Broadcast<List<Vertex>> bcVertices = sc.broadcast(vertices);
					while (iSegmentLevel < iVertexNum) {
						listSegment = u.getPathFromSegment(sc.parallelize(listSegment), bcOneSegment, bcVertices);
						if (listSegment.isEmpty()) {
							break;
						} else {
							mongospark.insertSegmentToMongoDB(listSegment);
							iSegmentLevel++;
						}
					}

					BigDecimal bdMax = BigDecimal.ZERO;
					String sKP = "";
					System.out.println("Sức ảnh hưởng của tất cả các đỉnh:");

					for (Vertex vertex : vertices) {
						String sVName = vertex.getName();
						BigDecimal bdTemp = u
								.getVertexIndirectInfluenceFromAllPath(mongospark.getVertexSegment(sVName));
						System.out.println(sVName + ": " + bdTemp.toPlainString());
						if (bdTemp.compareTo(bdMax) == 1) {
							bdMax = bdTemp;
							sKP = sVName;
						}
					}

					System.out.println("Key Player là: ");
					System.out.println(sKP + ": " + bdMax.toPlainString());
				}
				
				if (args[3].equals("c3")) {
					//Khởi tạo ma trận kết quả
					//Map<String[], BigDecimal> mapResult = new HashMap<String[], BigDecimal>();
					Map<String, Map<String, BigDecimal>> mapResult = new HashMap<String, Map<String, BigDecimal>>();
					
					//Khởi tạo Thread lưu ma trận kết quả
					Thread SavingThread;
					
					//Tính toán đoạn 1 đơn vị
					List<Segment> listOneSegment = u.getSegmentFromEdges(vertices, edges);
					
					//Lưu kết quả 1 đơn vị
					SavingThread = new MatrixResultFromSegment(mapResult, listOneSegment);
					SavingThread.start();
					
					//MongoDBSpark mongospark = new MongoDBSpark();
					//mongospark.insertSegmentToMongoDB(listOneSegment);
					int iSegmentLevel = 2;
					List<Segment> listSegment = listOneSegment;
					Broadcast<List<Segment>> bcOneSegment = sc.broadcast(listOneSegment);
					Broadcast<List<Vertex>> bcVertices = sc.broadcast(vertices);
					while (iSegmentLevel < iVertexNum) {
						listSegment = u.getPathFromSegment(sc.parallelize(listSegment), bcOneSegment, bcVertices);
						if (listSegment.isEmpty()) {
							break;
						} else {
							//mongospark.insertSegmentToMongoDB(listSegment);
							if (SavingThread.isAlive()){
								try {
									SavingThread.join();
								} catch (InterruptedException e) {
									// TODO Auto-generated catch block
									e.printStackTrace();
								}
							}
							SavingThread = new MatrixResultFromSegment(mapResult, listSegment);
							SavingThread.start();
							iSegmentLevel++;
						}
					}
					
					if (SavingThread.isAlive()){
						try {
							SavingThread.join();
						} catch (InterruptedException e) {
							// TODO Auto-generated catch block
							e.printStackTrace();
						}
					}

					/*BigDecimal bdMax = BigDecimal.ZERO;
					String sKP = "";
					System.out.println("Sức ảnh hưởng của tất cả các đỉnh:");
					
					List<Tuple2<String, BigDecimal>> listInInf = u.getAllVertexInInfFromMatrix(sc.broadcast(mapResult), sc.parallelize(vertices));
					
					for (Tuple2<String, BigDecimal> tuple : listInInf) {
						System.out.println(tuple._1 + ": " + tuple._2.toPlainString());
						if (tuple._2.compareTo(bdMax) == 1){
							bdMax = tuple._2;
							sKP = tuple._1;
						}
					}

					System.out.println("Key Player là: ");
					System.out.println(sKP + ": " + bdMax.toPlainString());*/
					
					Entry<String, BigDecimal> KP = u.getKeyPlayerFromMatrix(mapResult, vertices);
					System.out.println("Key Player là: ");
					System.out.println(KP.getKey() + ": " + KP.getValue().toPlainString());
				}
			}

			if (args[2].equals("-b3")) {
				lStart2 = System.currentTimeMillis();
				
				System.out.println("Ngưỡng sức ảnh hưởng là: " + args[3]);
				Data.theta = new BigDecimal(args[3]);
				System.out.println("Ngưỡng số đỉnh chịu sức ảnh hưởng là: " + args[4]);
				Data.iNeed = Integer.parseInt(args[4]);
				JavaPairRDD<String, List<String>> inif = u.getIndirectInfluence(vertices, edges);
				System.out.println("Sức ảnh hưởng vượt ngưỡng của tất cả các đỉnh:");

				// In ra danh sách các đỉnh và các đỉnh chịu sức ảnh hưởng vượt
				// ngưỡng từ các đỉnh đó
				inif.foreach(f -> {
					System.out.print("\n" + f._1 + " : [");
					for (String string : f._2) {
						System.out.print(string + ", ");
					}
					System.out.print("]\n");
				});
				//

				String kp = u.getTheMostOverThresholdVertexName(vertices, edges);
				List<String> res = u.getSmallestGroup(vertices, edges);
				System.out.println("Đỉnh có sức ảnh hưỡng vượt ngưỡng đến các đỉnh khác nhiều nhất là: " + kp.toString());

				System.out.println("Nhóm nhỏ nhất thỏa ngưỡng là: " + res);
			}
			
			if (args[2].equals("-ii")){
				lStart2 = System.currentTimeMillis();
				System.out.println("Sức ảnh hưởng gián tiếp của đỉnh " + args[3] + " là: "
						+ u.IndirectInfluenceOfVertexOnAllVertex(vertices, sc.parallelize(vertices), edges, args[3]));
			}

			long lEnd = System.currentTimeMillis();

			System.out.println("Thời gian tính toán tổng cộng là: " + (lEnd - lStart) + " ms");
			
			System.out.println("Thời gian tính toán không tính thời gian tạo đồ thị là: " + (lEnd - lStart2) + " ms");
		}
	}
}
