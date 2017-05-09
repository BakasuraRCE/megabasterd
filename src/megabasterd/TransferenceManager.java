package megabasterd;

import java.awt.Component;
import java.awt.TrayIcon;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.logging.Level;
import static java.util.logging.Logger.getLogger;
import javax.swing.JPanel;
import static megabasterd.MainPanel.THREAD_POOL;
import static megabasterd.MiscTools.swingReflectionInvoke;
import static megabasterd.MiscTools.swingReflectionInvokeAndWaitForReturn;

/**
 *
 * @author tonikelope
 */
abstract public class TransferenceManager implements Runnable, SecureSingleThreadNotifiable {

    private final ConcurrentLinkedQueue<Transference> _transference_provision_queue;
    private final ConcurrentLinkedQueue<Transference> _transference_waitstart_queue;
    private final ConcurrentLinkedQueue<Transference> _transference_remove_queue;
    private final ConcurrentLinkedQueue<Transference> _transference_finished_queue;
    private final ConcurrentLinkedQueue<Transference> _transference_running_list;
    private final ConcurrentLinkedQueue<Runnable> _transference_preprocess_queue;
    private final javax.swing.JPanel _scroll_panel;
    private final javax.swing.JLabel _status;
    private final javax.swing.JButton _close_all_button;
    private final javax.swing.JButton _pause_all_button;
    private final javax.swing.MenuElement _clean_all_menu;
    private int _max_running_trans;
    private final MainPanel _main_panel;
    private final Object _secure_notify_lock;
    private final Object _pre_lock;
    private boolean _notified;
    private volatile boolean _removing_transferences;
    private volatile boolean _provisioning_transferences;
    private volatile boolean _starting_transferences;
    private volatile boolean _preprocessing_transferences;
    private volatile int _pre_count;
    private volatile boolean _tray_icon_finish;

    public TransferenceManager(MainPanel main_panel, int max_running_trans, javax.swing.JLabel status, javax.swing.JPanel scroll_panel, javax.swing.JButton close_all_button, javax.swing.JButton pause_all_button, javax.swing.MenuElement clean_all_menu) {
        _notified = false;
        _removing_transferences = false;
        _provisioning_transferences = false;
        _starting_transferences = false;
        _preprocessing_transferences = false;
        _tray_icon_finish = false;
        _pre_count = 0;
        _main_panel = main_panel;
        _max_running_trans = max_running_trans;
        _scroll_panel = scroll_panel;
        _status = status;
        _close_all_button = close_all_button;
        _pause_all_button = pause_all_button;
        _clean_all_menu = clean_all_menu;
        _secure_notify_lock = new Object();
        _pre_lock = new Object();
        _transference_waitstart_queue = new ConcurrentLinkedQueue<>();
        _transference_provision_queue = new ConcurrentLinkedQueue<>();
        _transference_remove_queue = new ConcurrentLinkedQueue<>();
        _transference_finished_queue = new ConcurrentLinkedQueue<>();
        _transference_running_list = new ConcurrentLinkedQueue<>();
        _transference_preprocess_queue = new ConcurrentLinkedQueue<>();
    }

    abstract public void provision(Transference transference);

    abstract public void remove(Transference[] transference);

    public boolean isRemoving_transferences() {
        return _removing_transferences;
    }

    public void setRemoving_transferences(boolean removing) {
        _removing_transferences = removing;
    }

    public boolean isProvisioning_transferences() {
        return _provisioning_transferences;
    }

    public void setProvisioning_transferences(boolean provisioning) {
        _provisioning_transferences = provisioning;
    }

    public boolean isStarting_transferences() {
        return _starting_transferences;
    }

    public void setStarting_transferences(boolean starting) {
        _starting_transferences = starting;
    }

    public void setPreprocessing_transferences(boolean preprocessing) {
        _preprocessing_transferences = preprocessing;
    }

    public ConcurrentLinkedQueue<Runnable> getTransference_preprocess_queue() {
        return _transference_preprocess_queue;
    }
    
    public void addPre_count(int pre_count) {

        synchronized (_pre_lock) {

            _pre_count += pre_count;

            if (_pre_count < 0) {

                _pre_count = 0;
            }
        }
    }

    public void setMax_running_trans(int _max_running_trans) {
        this._max_running_trans = _max_running_trans;
    }

    @Override
    public void secureNotify() {
        synchronized (_secure_notify_lock) {

            _notified = true;

            _secure_notify_lock.notify();
        }
    }

    @Override
    public void secureWait() {

        synchronized (_secure_notify_lock) {
            while (!_notified) {

                try {
                    _secure_notify_lock.wait();
                } catch (InterruptedException ex) {
                    getLogger(TransferenceManager.class.getName()).log(Level.SEVERE, null, ex);
                }
            }

            _notified = false;
        }
    }

    public MainPanel getMain_panel() {
        return _main_panel;
    }

    public int getPre_count() {
        return _pre_count;
    }

    public boolean isPreprocessing_transferences() {
        return _preprocessing_transferences;
    }

    public ConcurrentLinkedQueue<Transference> getTransference_provision_queue() {
        return _transference_provision_queue;
    }

    public ConcurrentLinkedQueue<Transference> getTransference_waitstart_queue() {
        return _transference_waitstart_queue;
    }

    public ConcurrentLinkedQueue<Transference> getTransference_remove_queue() {
        return _transference_remove_queue;
    }

    public ConcurrentLinkedQueue<Transference> getTransference_finished_queue() {
        return _transference_finished_queue;
    }

    public ConcurrentLinkedQueue<Transference> getTransference_running_list() {
        return _transference_running_list;
    }

    public JPanel getScroll_panel() {
        return _scroll_panel;
    }

    public void closeAllFinished() {

        for (Transference t : _transference_finished_queue) {

            if (!t.isStatusError()) {

                _transference_finished_queue.remove(t);
                _transference_remove_queue.add(t);
            }
        }

        secureNotify();
    }

    public void closeAllPreProWaiting() {
        _transference_preprocess_queue.clear();

        _pre_count = 0;

        _transference_provision_queue.clear();

        _transference_remove_queue.addAll(new ArrayList(_transference_waitstart_queue));

        _transference_waitstart_queue.clear();

        secureNotify();
    }

    public void start(Transference transference) {

        _transference_running_list.add(transference);

        _scroll_panel.add((Component) transference.getView(), 0);

        transference.start();

        secureNotify();
    }

    public void pauseAll() {
        for (Transference transference : _transference_running_list) {

            if (!transference.isPaused()) {

                transference.pause();
            }
        }

        secureNotify();
    }

    public void sortTransferenceStartQueue() {
        ArrayList<Transference> trans_list = new ArrayList(_transference_waitstart_queue);

        trans_list.sort(new Comparator<Transference>() {

            @Override
            public int compare(Transference o1, Transference o2) {

                return o1.getFile_name().compareToIgnoreCase(o2.getFile_name());
            }
        });

        _transference_waitstart_queue.clear();

        _transference_waitstart_queue.addAll(trans_list);
    }

    private void updateView() {

        if (!_transference_running_list.isEmpty()) {

            boolean show_pause_all = false;

            for (Transference trans : _transference_running_list) {

                if ((show_pause_all = !trans.isPaused())) {

                    break;
                }
            }

            swingReflectionInvoke("setVisible", _pause_all_button, show_pause_all);

        } else {

            swingReflectionInvoke("setVisible", _pause_all_button, false);
        }

        swingReflectionInvoke("setEnabled", _clean_all_menu, !_transference_preprocess_queue.isEmpty() || !_transference_provision_queue.isEmpty() || !_transference_waitstart_queue.isEmpty());

        if (!_transference_finished_queue.isEmpty() && _isOKFinishedInQueue()) {

            swingReflectionInvoke("setText", _close_all_button, "Close all OK finished");

            swingReflectionInvoke("setVisible", _close_all_button, true);

        } else {

            swingReflectionInvoke("setVisible", _close_all_button, false);
        }

        swingReflectionInvoke("setText", _status, genStatus());
    }

    private String genStatus() {

        int prov = _transference_provision_queue.size();

        int rem = _transference_remove_queue.size();

        int wait = _transference_waitstart_queue.size();

        int run = _transference_running_list.size();

        int finish = _transference_finished_queue.size();

        if (!_tray_icon_finish && finish > 0 && _pre_count + prov + wait + run == 0 && !(boolean) swingReflectionInvokeAndWaitForReturn("isVisible", _main_panel.getView())) {

            _tray_icon_finish = true;

            swingReflectionInvoke("displayMessage", _main_panel.getTrayicon(), "MegaBasterd says:", "All your transferences have finished", TrayIcon.MessageType.INFO);
        }

        return (_pre_count + prov + rem + wait + run + finish > 0) ? "Pre: " + _pre_count + " / Pro: " + prov + " / Wait: " + wait + " / Run: " + run + " / Finish: " + finish + " / Rem: " + rem : "";
    }

    private boolean _isOKFinishedInQueue() {

        for (Transference t : _transference_finished_queue) {

            if (!t.isStatusError()) {

                return true;
            }
        }

        return false;
    }

    @Override
    public void run() {

        while (true) {
            if (!isPreprocessing_transferences() && !getTransference_preprocess_queue().isEmpty()) {

                setPreprocessing_transferences(true);

                THREAD_POOL.execute(new Runnable() {
                    @Override
                    public void run() {

                        while (!getTransference_preprocess_queue().isEmpty()) {
                            Runnable run = getTransference_preprocess_queue().poll();

                            if (run != null) {

                                run.run();
                            }
                        }

                        setPreprocessing_transferences(false);

                        secureNotify();

                    }
                });
            }

            if (!isProvisioning_transferences() && !getTransference_provision_queue().isEmpty()) {

                setProvisioning_transferences(true);

                _tray_icon_finish = false;

                THREAD_POOL.execute(new Runnable() {
                    @Override
                    public void run() {

                        while (!getTransference_provision_queue().isEmpty()) {
                            Transference transference = getTransference_provision_queue().poll();

                            if (transference != null) {

                                provision(transference);

                            }
                        }

                        setProvisioning_transferences(false);

                        secureNotify();

                    }
                });

            }

            if (!isRemoving_transferences() && !getTransference_remove_queue().isEmpty()) {

                setRemoving_transferences(true);

                THREAD_POOL.execute(new Runnable() {
                    @Override
                    public void run() {

                        if (!getTransference_remove_queue().isEmpty()) {

                            ArrayList<Transference> transferences = new ArrayList(getTransference_remove_queue());

                            getTransference_remove_queue().clear();

                            remove(transferences.toArray(new Transference[transferences.size()]));
                        }

                        setRemoving_transferences(false);

                        secureNotify();

                    }
                });
            }

            if (!isStarting_transferences() && !getTransference_waitstart_queue().isEmpty() && getTransference_running_list().size() < _max_running_trans) {
                setStarting_transferences(true);

                THREAD_POOL.execute(new Runnable() {
                    @Override
                    public void run() {

                        while (!getTransference_waitstart_queue().isEmpty() && getTransference_running_list().size() < _max_running_trans) {

                            Transference transference = getTransference_waitstart_queue().poll();

                            if (transference != null) {

                                start(transference);
                            }
                        }
                        
                        setStarting_transferences(false);

                        secureNotify();

                    }
                });
            }

            updateView();

            secureWait();
        }

    }

}
