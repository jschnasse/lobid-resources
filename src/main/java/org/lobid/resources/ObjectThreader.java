package org.lobid.resources;

import org.culturegraph.mf.framework.DefaultTee;
import org.culturegraph.mf.framework.ObjectPipe;
import org.culturegraph.mf.framework.ObjectReceiver;
import org.culturegraph.mf.framework.Tee;
import org.culturegraph.mf.framework.annotations.In;
import org.culturegraph.mf.framework.annotations.Out;
import org.culturegraph.mf.stream.pipe.ObjectPipeDecoupler;

/**
 * Divides incoming objects and pass distribute them to added receivers. passed
 * to the receiver. Combine it with a
 * {@link org.culturegraph.mf.stream.pipe.ObjectPipeDecoupler} and add multiple
 * of these using a tee like e.g.
 * {@link org.culturegraph.mf.stream.pipe.ObjectTee} to get multithreading.
 * 
 * @param <T> Object type
 *
 * @author Pascal Christoph(dr0i)
 * 
 */
@In(Object.class)
@Out(Object.class)
public class ObjectThreader<T> extends DefaultTee<ObjectReceiver<T>>
		implements ObjectPipe<T, ObjectReceiver<T>> {

	private int objectNumber = 0;

	@Override
	public void process(final T obj) {
		getReceivers().get(objectNumber).process(obj);
		if (objectNumber == getReceivers().size() - 1)
			objectNumber = 0;
		else
			objectNumber++;
	}

	@Override
	public <R extends ObjectReceiver<T>> R setReceiver(final R receiver) {
		return super.setReceiver(
				new ObjectPipeDecoupler<T>().setReceiver(receiver));
	}

	@Override
	public Tee<ObjectReceiver<T>> addReceiver(final ObjectReceiver<T> receiver) {
		System.out.println("added instance number" + getReceivers().size());
		ObjectPipeDecoupler<T> opd = new ObjectPipeDecoupler<>();
		opd.setReceiver(receiver);
		return super.addReceiver(opd);
	}
}
