package seref;
import com.hazelcast.config.Config;
import com.hazelcast.config.ExecutorConfig;
import com.hazelcast.config.ManagementCenterConfig;
import com.hazelcast.config.MapConfig;
import com.hazelcast.config.QueueConfig;
import com.hazelcast.core.Hazelcast;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.core.IAtomicLong;
import com.hazelcast.core.IExecutorService;
import com.hazelcast.core.IMap;
import com.hazelcast.core.IQueue;
import com.hazelcast.core.ITopic;
import com.hazelcast.core.IdGenerator;
import com.hazelcast.config.ClasspathXmlConfig;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public class  ServerPA<T> {

	private HazelcastInstance instance;
	private int poolSize = 16;
	private long myID = -1;
	IMap<T, Vertex> vertexMap;
	IMap<T, Integer> qMap;
	IQueue<T> queue, nqueue;
	IAtomicLong counter;
	IAtomicLong opCtr;
	Set<T> localKeySet;
	IMap<Integer, T> partitionMap;
	Class clazz;
	T key, key2;
	int p, nodeCount, partitionCount;
	IExecutorService updateExecutorService;
	private int wNum=-1;
	long t1,t2;
	Integer one = new Integer(1);


	public void initInstance(String job){
		Config cfg = new ClasspathXmlConfig("hazelcast.xml");
		
		ManagementCenterConfig managementCenterConfig = new ManagementCenterConfig();
		managementCenterConfig.setEnabled(true);
		managementCenterConfig.setUrl("http://localhost:8080/mancenter");
		cfg.setManagementCenterConfig(managementCenterConfig);
		cfg.getGroupConfig().setName("3.0");
		
		MapConfig mapCfg = new MapConfig();
		mapCfg.setName("vertex");
		mapCfg.setBackupCount(0);
		cfg.addMapConfig(mapCfg);
		
		QueueConfig qCfg = new QueueConfig();
		qCfg.setName("queue");
		qCfg.setBackupCount(0);
		cfg.addQueueConfig(qCfg);
		
		ExecutorConfig eCfg = new ExecutorConfig();
		eCfg.setPoolSize(poolSize);
		cfg.addExecutorConfig(eCfg);
		
		cfg.getSerializationConfig().addPortableFactory(1, new PortableVertexFactory());
		if(job.equals("ep")){
			cfg.getMapConfig("vertex").setInMemoryFormat(MapConfig.DEFAULT_IN_MEMORY_FORMAT.OBJECT);
		}
		
		System.setProperty("hazelcast.partition.count", "2");

		instance = Hazelcast.newHazelcastInstance(cfg);
		
		vertexMap = instance.getMap("vertex");
		qMap = instance.getMap("qMap");
		
		counter = instance.getAtomicLong("counter");
		opCtr = instance.getAtomicLong("operationCounter");
		partitionMap = instance.getMap("partitionMap");
		updateExecutorService = instance.getExecutorService("updateExecutorPA");
		
		IAtomicLong idGenerator = instance.getAtomicLong("worker-ids");
		myID = idGenerator.getAndIncrement();
	}

	
	public void lockNeighborhood(Vertex vertex){
		ArrayList<Integer> nn = vertex.getOutNeighbors();
		nn.add(vertex.getVertexID());
		Collections.sort(nn);
		for(Integer neighbor : nn){
			//System.out.println("Locking "+neighbor);
			if(clazz == PAKey.class){
//				key = partitionMap.get(neighbor);
				p = findP(neighbor, nodeCount, partitionCount);
				key = (T) new PAKey(neighbor.intValue(), p+"");
			}else{
				key = (T) neighbor;
			}
			vertexMap.lock(key);
		}
		nn.remove(new Integer(vertex.getVertexID()));
	}
	public void unLockNeighborhood(Vertex vertex){
		ArrayList<Integer> nn = vertex.getOutNeighbors();
		nn.add(vertex.getVertexID());
		Collections.sort(nn);
		for(Integer neighbor : vertex.getOutNeighbors()){
			//System.out.println("Unlocking "+neighbor);
			if(clazz == PAKey.class){
//				key = partitionMap.get(neighbor);
				p = findP(neighbor, nodeCount, partitionCount);
				key = (T) new PAKey(neighbor.intValue(), p+"");
			}else{
				key = (T) neighbor;
			}
			vertexMap.unlock(key);
		}
		nn.remove(new Integer(vertex.getVertexID()));
	}

	public int findP(int ID, int nodeCount, int partitionCount){
		double sqrtPC=(Math.floor(Math.sqrt(partitionCount)));
		double pn = Math.floor(Math.sqrt(nodeCount)/sqrtPC);
	    int column = (int) (ID%Math.floor(Math.sqrt(nodeCount)));
	    int row = (int) (Math.floor(ID/Math.sqrt(nodeCount)));
	    int p = (int) (Math.floor(row/pn)*sqrtPC + Math.floor(column/pn));
	    return p % wNum;
	}

	public void compute(Vertex vertex){
		int nextColor = -1;
		int curColor = (int) vertex.getValue();
		double elapsedTime;
		long t1,t2;

		int[] colorMap = new int[2*vertex.getOutNeighbors().size()];
		for(int i=0; i<colorMap.length; i++){ colorMap[i]=0;}
//		System.out.println(vertex.getVertexID()+" -> "+ vertex.getValue()+":");
//		t1 = System.nanoTime(); 

		for(Integer neighbor : vertex.getOutNeighbors()){
	    	
			if(clazz == PAKey.class){
//				key = partitionMap.get(neighbor);
				p = findP(neighbor, nodeCount, partitionCount);
				key = (T) new PAKey(neighbor.intValue(), p+"");
			}else{
				key = (T) neighbor;
			}
//			System.out.println("Reading neighbor: "+key+" "+neighbor+" "+p);
			int nColor = (int) vertexMap.get(key).getValue();
			if(nColor < colorMap.length){
				colorMap[nColor]=1;
			}
		}
//		t2 = System.nanoTime(); 
//		elapsedTime = (t2 - t1)/(Math.pow(10, 6));
//		System.out.println("Get color neighborhood takes: "+elapsedTime);
//		long t4 = System.nanoTime(); 

		for(int i=0; i<colorMap.length; i++){
			if(colorMap[i]!=1){
				nextColor=i;
//				System.out.println("Next color is:"+nextColor);
				break;
			}
		}

		if(curColor != nextColor){
			vertex.setValue(nextColor);
			if(clazz == PAKey.class){
				key2 = (T) new PAKey(vertex.getVertexID(), vertex.getPartitionID()+"");
//				System.out.println("PAKey:"+key2);
			}else{
				key2 = (T) new Integer(vertex.getVertexID());
			}
//			t1 = System.nanoTime(); 
			vertexMap.put(key2, vertex);
//			t2 = System.nanoTime(); 
//			elapsedTime = (t2 - t1)/(Math.pow(10, 6));
//			System.out.println("Put vertex takes: "+elapsedTime);

//			System.out.println("Map size is:"+vertexMap.size());

			for(Integer neighbor : vertex.getOutNeighbors()){
				if(clazz == PAKey.class){
//					key = partitionMap.get(neighbor);
					p = findP(neighbor, nodeCount, partitionCount);
					key = (T) new PAKey(neighbor.intValue(), p+"");
				}else{
					key = (T) neighbor;
				}

				nqueue = instance.getQueue("queue@"+p);
//				long t3 = System.nanoTime(); 
				if(qMap.get(key)==null){
					nqueue.add(key);
					qMap.put(key, one);
				}
//				t2 = System.nanoTime(); 
//				elapsedTime = (t2 - t3)/(Math.pow(10, 6));
//				System.out.println("Contains and Add queue takes: "+elapsedTime);

			}
		}
//		t2 = System.nanoTime(); 
//		elapsedTime = (t2 - t4)/(Math.pow(10, 6));
//		System.out.println("Enqueue neighborhood takes: "+elapsedTime);


		long ctr = opCtr.incrementAndGet();
		//System.out.println("Committed "+vertex.getId());
		//System.out.println("Operation count is "+ctr);
	}
	
	public void initQueue(){
		localKeySet = vertexMap.localKeySet();
		for (T key : localKeySet){
//			System.out.println("Vertex is: "+key+" -> "+ vertexMap.get(key).getValue()+" myID: "+myID);
			queue = instance.getQueue("queue@"+myID);
			queue.add(key);
			qMap.put(key, one);
		}
	}
	
	public void printLocalVertices(){
		for (T key : vertexMap.localKeySet()){
			System.out.println("Vertex is: "+key+" -> "+ vertexMap.get(key).getValue());
		}

	}

	public void printPartitionMap(){
		for (Integer key : partitionMap.localKeySet()){
			System.out.println("Vertex is: "+key+" -> "+ partitionMap.get(key));
		}

	}

	public void executeAllVertices(long startTime, String job){
		double elapsedTime=-1.0;
		ExecutorService executor = Executors.newFixedThreadPool(16);

		
		queue = instance.getQueue("queue@"+myID);
		int ctr=0;
		while(queue.size()>0 || counter.get()>0 ){
//			CommonTools.getInput();
			T key=null;
			if(queue.size()>0){
				key = (T) queue.remove();
				qMap.delete(key);
			}
			if(key!=null){
				Vertex vertex= vertexMap.get(key);
				counter.incrementAndGet();	
//				Runnable worker = new WorkerThread(vertexMap.get(key), vertexMap, queue, opCtr, key);
//	            executor.execute(worker);
				
//				t1 = System.nanoTime(); 
				lockNeighborhood(vertex);
//				t2 = System.nanoTime(); 
//				elapsedTime = (t2 - t1)/(Math.pow(10, 6));
//				System.out.println("Lock neighborhood takes: "+elapsedTime);
//				t1 = System.nanoTime(); 
				compute(vertex);	
//				t2 = System.nanoTime(); 
//				elapsedTime = (t2 - t1)/(Math.pow(10, 6));
//				System.out.println("Compute vertex takes: "+elapsedTime);
//				t1 = System.nanoTime(); 
				unLockNeighborhood(vertex);
//				t2 = System.nanoTime(); 
//				elapsedTime = (t2 - t1)/(Math.pow(10, 6));
//				System.out.println("UnLock neighborhood takes: "+elapsedTime);
				
//				Future<Integer> future = updateExecutorService.submitToKeyOwner(
//						new UpdateExecutorPA(key, clazz, nodeCount, partitionCount, job, myID, wNum), key);
//				try { future.get();
//				} catch (InterruptedException | ExecutionException e) {e.printStackTrace();}	

				counter.decrementAndGet();
			}
//			System.out.println("Queue size is:"+queue.size()
//					+" and "+queue.getLocalQueueStats().getOwnedItemCount());
			if(opCtr.get()%1000==0 && opCtr.get()>0){
				elapsedTime = (System.nanoTime() - startTime)/(Math.pow(10, 9));
				System.out.println(opCtr.get()+" Elapsed time is:"+elapsedTime);
			}
		}
//		executor.shutdown();
//        while (!executor.isTerminated()) {}
		ITopic<String> topic2 = instance.getTopic("terminator");
		topic2.publish("Ready");
		CommonTools.waitLoading(instance);

//		updateExecutorService.shutdown();
//		try {
//			updateExecutorService.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS);
//		} catch (InterruptedException e) {
//			System.out.println("Error in waiting for termination of DE. Error is: "+e);
//		}

	}


	public static void main(String[] args) {    
		ServerPA<PAKey> clientP = new ServerPA<PAKey>();
		ServerPA<Integer> clientI = new ServerPA<Integer>();
		ServerPA myClient = null;
		String cl = "integer";
    	if(args.length>0) cl = args[0];
    	Class clazz = Integer.class;
    	if(cl.equals("pakey")){
    		clazz = PAKey.class;
    		myClient = clientP;
    	}else if(cl.equals("integer")){
    		clazz = Integer.class;
    		myClient = clientI;
    	}
    	myClient.clazz = clazz;
    	
    	String job = "ml";
    	if(args.length>1) job = args[1];
    	if(args.length>2) myClient.wNum = Integer.parseInt(args[2]);
    	
		double elapsedTime=-1.0;
		
		myClient.initInstance(job);
		CommonTools.waitLoading(myClient.instance);
		myClient.initQueue();
		myClient.nodeCount = myClient.vertexMap.size();
		if(myClient.nodeCount == 16){
			myClient.partitionCount = 4;  //16vertex ise 4, diger gibi 16 olmali			
		}
		else{
			myClient.partitionCount = 16;  //16vertex ise 4, diger gibi 16 olmali
		}

//		myClient.printLocalVertices();
//		myClient.printPartitionMap();

		//CommonTools.benchmark(queue, vertexMap, counter);
		//CommonTools.getInput();
		long startTime = System.nanoTime();    

		myClient.executeAllVertices(startTime, job);

		elapsedTime = (System.nanoTime() - startTime)/(Math.pow(10, 9));
		System.out.println("Total time: "+elapsedTime);

		myClient.printLocalVertices();
		
		myClient.instance.getLifecycleService().shutdown();
	}
	
	
	
	public class WorkerThread implements Runnable {
		Vertex vertex; 
		IMap<Integer, Vertex> vertexMap;
		IQueue<Integer> queue; 
		IAtomicLong opCtr;
		Integer key;

		public WorkerThread(Vertex vertex, IMap<Integer, Vertex> vertexMap,
				IQueue<Integer> queue, IAtomicLong opCtr, Integer key) {
			this.vertex= vertex;
			this.vertexMap = vertexMap;
			this.opCtr = opCtr;
			this.queue = queue;
			this.key = key;	
		}

		@Override
	    public void run() {
	    	CommonTools.lockNeighborhood(vertexMap,vertex);
	    	ServerPA.this.compute(vertexMap.get(key));	
			CommonTools.unLockNeighborhood(vertexMap,vertex);
	    }

	}

}
