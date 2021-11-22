package com.shimizukenta.secs;

import java.io.IOException;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import com.shimizukenta.secs.gem.Gem;
import com.shimizukenta.secs.secs2.Secs2;
import com.shimizukenta.secs.sml.SmlMessage;

/**
 * This abstract class is implementation of SECS-communicate.
 * 
 * @author kenta-shimizu
 *
 */
public abstract class AbstractSecsCommunicator extends AbstractBaseCommunicator implements SecsCommunicator {
	
	private final BooleanProperty communicatable = BooleanProperty.newInstance(false);
	
	private final AbstractSecsCommunicatorConfig config;
	private final Gem gem;
	
	public AbstractSecsCommunicator(AbstractSecsCommunicatorConfig config) {
		super();
		
		this.config = config;
		this.gem = Gem.newInstance(this, config.gem());
	}
	
	@Override
	public void open() throws IOException {
		
		super.open();
		
		executeLogQueueTask();
		executeMsgRecvQueueTask();
		executeTrySendMsgPassThroughQueueTask();
		executeSendedMsgPassThroughQueueTask();
		executeRecvMsgPassThroughQueueTask();
	}
	
	@Override
	public void close() throws IOException {
		super.close();
	}
	
	@Override
	public Gem gem() {
		return gem;
	}
	
	@Override
	public boolean isEquip() {
		return config.isEquip().booleanValue();
	}
	
	@Override
	public void openAndWaitUntilCommunicating() throws IOException, InterruptedException {
		
		synchronized ( this ) {
			if ( ! isOpen() ) {
				open();
			}
		}
		
		communicatable.waitUntilTrue();
	}
	
	@Override
	public Optional<SecsMessage> send(int strm, int func, boolean wbit)
			throws SecsSendMessageException, SecsWaitReplyMessageException, SecsException
			, InterruptedException {
		
		return send(strm, func, wbit, Secs2.empty());
	}
	
	@Override
	public Optional<SecsMessage> send(SecsMessage primaryMsg, int strm, int func, boolean wbit)
			throws SecsSendMessageException, SecsWaitReplyMessageException, SecsException
			, InterruptedException {
		
		return send(primaryMsg, strm, func, wbit, Secs2.empty());
	}
	
	@Override
	public Optional<SecsMessage> send(SmlMessage sml)
			throws SecsSendMessageException, SecsWaitReplyMessageException, SecsException
			, InterruptedException {
		
		return send(sml.getStream(), sml.getFunction(), sml.wbit(), sml.secs2());
	}
	
	@Override
	public Optional<SecsMessage> send(SecsMessage primaryMsg, SmlMessage sml)
			throws SecsSendMessageException, SecsWaitReplyMessageException, SecsException
			, InterruptedException {
		
		return send(primaryMsg, sml.getStream(), sml.getFunction(), sml.wbit(), sml.secs2());
	}
	
	
	/* Secs-Message Receive Listener */
	private final Collection<SecsMessageReceiveListener> msgRecvListeners = new CopyOnWriteArrayList<>();
	
	@Override
	public boolean addSecsMessageReceiveListener(SecsMessageReceiveListener l) {
		return msgRecvListeners.add(Objects.requireNonNull(l));
	}
	
	@Override
	public boolean removeSecsMessageReceiveListener(SecsMessageReceiveListener l) {
		return msgRecvListeners.remove(Objects.requireNonNull(l));
	}
	
	private final Collection<SecsMessageReceiveBiListener> msgRecvBiListeners = new CopyOnWriteArrayList<>();
	
	@Override
	public boolean addSecsMessageReceiveListener(SecsMessageReceiveBiListener l) {
		return msgRecvBiListeners.add(Objects.requireNonNull(l));
	}
	
	@Override
	public boolean removeSecsMessageReceiveListener(SecsMessageReceiveBiListener l) {
		return msgRecvBiListeners.remove(Objects.requireNonNull(l));
	}
	
	private final BlockingQueue<SecsMessage> msgRecvQueue = new LinkedBlockingQueue<>();
	
	private void executeMsgRecvQueueTask() {
		executeLoopTask(() -> {
			SecsMessage msg = msgRecvQueue.take();
			msgRecvListeners.forEach(l -> {
				l.received(msg);
			});
			msgRecvBiListeners.forEach(l -> {
				l.received(msg, this);
			});
		});
	}
	
	public void notifyReceiveMessage(SecsMessage msg) throws InterruptedException {
		msgRecvQueue.put(msg);
	}
	
	
	/* Secs-Log Receive Listener */
	private final Collection<SecsLogListener> logListeners = new CopyOnWriteArrayList<>();
	
	@Override
	public boolean addSecsLogListener(SecsLogListener l) {
		return logListeners.add(Objects.requireNonNull(l));
	}
	
	@Override
	public boolean removeSecsLogListener(SecsLogListener l) {
		return logListeners.remove(Objects.requireNonNull(l));
	}
	
	private final BlockingQueue<SecsLog> logQueue = new LinkedBlockingQueue<>();
	
	private void executeLogQueueTask() {
		
		this.executorService().execute(() -> {
			
			try {
				for ( ;; ) {
					final SecsLog log = this.logQueue.take();
					logListeners.forEach(l -> {
						l.received(log);
					});
				}
			}
			catch ( InterruptedException ignore ) {
			}
			
			try {
				for ( ;; ) {
					
					final SecsLog log = this.logQueue.poll(100L, TimeUnit.MILLISECONDS);
					if ( log == null ) {
						break;
					}
					logListeners.forEach(l -> {
						l.received(log);
					});
				}
			}
			catch ( InterruptedException ignore ) {
			}
		});
	}
	
	public void notifyLog(AbstractSecsLog log) throws InterruptedException {
		log.subjectHeader(this.config.logSubjectHeader().get());
		this.logQueue.put(log);
	}
	
	protected void notifyLog(Throwable t) throws InterruptedException {
		
		this.notifyLog(new AbstractSecsThrowableLog(t) {
			
			private static final long serialVersionUID = -1271705310309086030L;
		});
	}
	
	
	/* Secs-Communicatable-State-Changed-Listener */
	
	@Override
	public boolean addSecsCommunicatableStateChangeListener(SecsCommunicatableStateChangeListener l) {
		return this.communicatable.addChangeListener(l::changed);
	}
	
	@Override
	public boolean removeSecsCommunicatableStateChangeListener(SecsCommunicatableStateChangeListener l) {
		return this.communicatable.removeChangeListener(l::changed);
	}
	
	private final Map<SecsCommunicatableStateChangeBiListener, SecsCommunicatableStateChangeListener> biCommStateMap = new HashMap<>();
	
	@Override
	public boolean addSecsCommunicatableStateChangeListener(SecsCommunicatableStateChangeBiListener l) {
		
		final SecsCommunicatableStateChangeListener x = f -> {
			l.changed(f, this);
		};
		
		synchronized ( this.biCommStateMap ) {
			this.biCommStateMap.put(l, x);
			return this.communicatable.addChangeListener(x::changed);
		}
	}
	
	@Override
	public boolean removeSecsCommunicatableStateChangeListener(SecsCommunicatableStateChangeBiListener l) {
		synchronized ( this.biCommStateMap ) {
			final SecsCommunicatableStateChangeListener x = this.biCommStateMap.remove(l);
			if ( x != null ) {
				return this.communicatable.removeChangeListener(x::changed);
			}
			return false;
		}
	}
	
	public void notifyCommunicatableStateChange(boolean f) {
		this.communicatable.set(f);
	}
	
	
	/* Try-Send Secs-Message Pass-through Listener */
	private final Collection<SecsMessagePassThroughListener> trySendMsgPassThroughListeners = new CopyOnWriteArrayList<>();
	
	@Override
	public boolean addTrySendMessagePassThroughListener(SecsMessagePassThroughListener l) {
		return trySendMsgPassThroughListeners.add(Objects.requireNonNull(l));
	}
	
	@Override
	public boolean removeTrySendMessagePassThroughListener(SecsMessagePassThroughListener l) {
		return trySendMsgPassThroughListeners.remove(Objects.requireNonNull(l));
	}
	
	private BlockingQueue<SecsMessage> trySendMsgPassThroughQueue = new LinkedBlockingQueue<>();
	
	private void executeTrySendMsgPassThroughQueueTask() {
		executeLoopTask(() -> {
			SecsMessage msg = trySendMsgPassThroughQueue.take();
			trySendMsgPassThroughListeners.forEach(l -> {l.passThrough(msg);});
		});
	}
	
	public void notifyTrySendMessagePassThrough(SecsMessage msg) throws InterruptedException {
		trySendMsgPassThroughQueue.put(msg);
	}
	
	
	/* Sended Secs-Message Pass-through Listener */
	private final Collection<SecsMessagePassThroughListener> sendedMsgPassThroughListeners = new CopyOnWriteArrayList<>();
	
	@Override
	public boolean addSendedMessagePassThroughListener(SecsMessagePassThroughListener l) {
		return sendedMsgPassThroughListeners.add(Objects.requireNonNull(l));
	}
	
	@Override
	public boolean removeSendedMessagePassThroughListener(SecsMessagePassThroughListener l) {
		return sendedMsgPassThroughListeners.remove(Objects.requireNonNull(l));
	}
	
	private final BlockingQueue<SecsMessage> sendedMsgPassThroughQueue = new LinkedBlockingQueue<>();
	
	private void executeSendedMsgPassThroughQueueTask() {
		executeLoopTask(() -> {
			SecsMessage msg = sendedMsgPassThroughQueue.take();
			sendedMsgPassThroughListeners.forEach(l -> {l.passThrough(msg);});
		});
	}
	
	public void notifySendedMessagePassThrough(SecsMessage msg) throws InterruptedException {
		sendedMsgPassThroughQueue.put(msg);
	}
	
	/* Receive Secs-Message Pass-through Listener */
	private final Collection<SecsMessagePassThroughListener> recvMsgPassThroughListeners = new CopyOnWriteArrayList<>();
	
	@Override
	public boolean addReceiveMessagePassThroughListener(SecsMessagePassThroughListener l) {
		return recvMsgPassThroughListeners.add(Objects.requireNonNull(l));
	}
	
	@Override
	public boolean removeReceiveMessagePassThroughListener(SecsMessagePassThroughListener l) {
		return recvMsgPassThroughListeners.remove(Objects.requireNonNull(l));
	}
	
	private final BlockingQueue<SecsMessage> recvMsgPassThroughQueue = new LinkedBlockingQueue<>();
	
	private void executeRecvMsgPassThroughQueueTask() {
		executeLoopTask(() -> {
			SecsMessage msg = recvMsgPassThroughQueue.take();
			recvMsgPassThroughListeners.forEach(l -> {l.passThrough(msg);});
		});
	}
	
	public void notifyReceiveMessagePassThrough(SecsMessage msg) throws InterruptedException {
		recvMsgPassThroughQueue.put(msg);
	}
	
}
