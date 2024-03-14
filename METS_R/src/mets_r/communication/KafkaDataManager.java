package mets_r.communication;

import java.util.Properties;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.apache.kafka.clients.producer.*;

import com.vividsolutions.jts.geom.Coordinate;

import mets_r.data.output.CV2XSnapshot;
import mets_r.mobility.Vehicle;


public class KafkaDataManager{
	private static final String BOOTSTRAP_SERVERS = "localhost:29092";
	private Producer<String, String> cv2xProducer;
	private ExecutorService executorService;
	private CompletionService<String> compService;
	
	public KafkaDataManager(){
		Properties props = new Properties();
		props.put("bootstrap.servers", BOOTSTRAP_SERVERS);

		props.put("key.serializer", "org.apache.kafka.common.serialization.StringSerializer");
		props.put("value.serializer", "org.apache.kafka.common.serialization.StringSerializer");
		
		// TODO: further tune the producer settings, Eunhan
		cv2xProducer = new KafkaProducer<String, String>(props);
		
		executorService = Executors.newFixedThreadPool(2); // Use two threads to send Kafka message
		compService = new ExecutorCompletionService<String>(executorService);
	}
	
	public void cv2xProduce(Vehicle vehicle, Coordinate coordinate) {
		CV2XSnapshot mySnapshot = new CV2XSnapshot(vehicle, coordinate);
		String key = Integer.toString(mySnapshot.hashCode());
		String message = mySnapshot.toString();
		System.out.println("Sent msg " + message);
		Callable<String> sender = ()->{
			cv2xProducer.send(new ProducerRecord<String, String>("cv2x", key, message));
			return "sent cv2x";
		};
		compService.submit(sender);
	}
}
