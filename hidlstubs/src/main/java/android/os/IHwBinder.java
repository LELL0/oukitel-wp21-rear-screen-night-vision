package android.os;
/** Compile-only stub. */
public interface IHwBinder {
    void transact(int code, HwParcel request, HwParcel reply, int flags) throws RemoteException;
}
