/* This class is part of the XP framework's EAS connectivity
 *
 * $Id: ServerHandler.java 12461 2008-09-09 14:50:40Z friebe $
 */

package net.xp_framework.easc.protocol.standard;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.HashMap;

import net.xp_framework.easc.server.Handler;
import net.xp_framework.easc.protocol.standard.MessageType;
import net.xp_framework.easc.util.ByteCountedString;
import net.xp_framework.easc.server.Delegate;
import net.xp_framework.easc.protocol.standard.Header;
import net.xp_framework.easc.server.ServerContext;

import static net.xp_framework.easc.protocol.standard.Header.DEFAULT_MAGIC_NUMBER;

abstract public class ServerHandler implements Handler {

    /**
     * Write response
     *
     * @access  protected
     * @param   java.io.DataOutputStream out
     * @param   net.xp_framework.easc.protocol.standard.MessageType type
     * @param   java.lang.String buffer the encoded data
     */
    protected void writeResponse(DataOutputStream out, MessageType type, String buffer) throws IOException {
        ByteCountedString bytes= new ByteCountedString(buffer);

        // Write header
        new Header(
            DEFAULT_MAGIC_NUMBER,
            (byte)1,
            (byte)0,
            type,
            false,
            bytes.length()
        ).writeTo(out);

        // Write data and flush
        bytes.writeTo(out);
        out.flush();
    }

    /**
     * Handle client connection
     *
     * @access  public
     * @param   java.io.DataInputStream in
     * @param   java.io.DataOutputStream out
     * @param   net.xp_framework.easc.server.ServerContext ctx
     */
    public void handle(DataInputStream in, DataOutputStream out, final ServerContext ctx) {        
        boolean done= false;
        // for this client store all lookups as strong references within this functions scope
        // the weak references stored in the context will be used to provide the same lookup for multiple clients
        // however if no client holds a lookup the weak references are subject to be garbage collected
        HashMap<Integer, Object> serviceObjects= new HashMap<Integer, Object>(); 
        while (!done) {
            try {
                Header requestHeader= Header.readFrom(in);

                // Verify magic number
                if (DEFAULT_MAGIC_NUMBER != requestHeader.getMagicNumber()) {
                    this.writeResponse(out, MessageType.Error, "Magic number mismatch");
                    break;
                }

                // Execute using delegate
                Object result= null;
                MessageType response= MessageType.Value;
                String buffer= null;
                try {
                    Delegate delegate= requestHeader.getMessageType().delegateFrom(in, ctx);
                    ClassLoader delegateCl = delegate.getClassLoader();

                    if (MessageType.Lookup == requestHeader.getMessageType()) {
                        // don't like this, since the caching actually should be done completely within the LookupDelegate
                        // but we don't have a suitable scope for strong references there...
                        // Maybe we would need a HandlerContext, created in the HandlerThread and passed over to the Delegates?
                        // That change would be to large for now I think.

                        Object delegateInvoke= delegate.invoke(ctx);
                        Integer delegateInvokeKey= delegateInvoke.hashCode();

                        synchronized (this) {
                            // ensure check and adding to the cache is only done once
                            // at a time
                            Object oldDelegateInvoke= null;
                            WeakReference cachedReference= ctx.objects.get(delegateInvokeKey);
                            if (cachedReference != null) {
                                oldDelegateInvoke= cachedReference.get();
                            }
                            if (oldDelegateInvoke == null) {
                                ctx.objects.put(delegateInvokeKey, new WeakReference(delegateInvoke));
                            } else {
                                // use the cached lookup
                                delegateInvoke= oldDelegateInvoke;
                            }
                        }

                        if (!serviceObjects.containsKey(delegateInvoke.hashCode())) {
                            // no strong reference set yet? -> set
                        	serviceObjects.put(delegateInvoke.hashCode(), delegateInvoke);
                        }
                        buffer= Serializer.representationOf(delegateInvoke);
                    } else if (null != delegateCl) {
                        Thread currentThread= Thread.currentThread();
                        ClassLoader currentCl= currentThread.getContextClassLoader();
                        currentThread.setContextClassLoader(delegateCl);
                        buffer= Serializer.representationOf(delegate.invoke(ctx), new SerializerContext(delegateCl));
                        currentThread.setContextClassLoader(currentCl);
                    } else {
                        buffer= Serializer.representationOf(delegate.invoke(ctx));
                    }
                } catch (Throwable t) {
                    // DEBUG t.printStackTrace();
                    try {
                        buffer= Serializer.representationOf(t);
                        response= MessageType.Exception;
                    } catch (Exception e) {
                        buffer= e.getMessage();
                        response= MessageType.Error;
                    }
                }

                // Write result
                this.writeResponse(out, response, buffer);
            } catch (IOException e) {
                // Presumably, the client has closed the connection
                done= true;
            }
        }
    }
}
