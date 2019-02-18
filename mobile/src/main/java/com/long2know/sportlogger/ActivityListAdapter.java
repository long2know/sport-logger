package com.long2know.sportlogger;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.TextView;

import com.long2know.utilities.data_access.SqlLogger;
import com.long2know.utilities.models.SportActivity;

import java.util.ArrayList;
import java.util.List;

public class ActivityListAdapter extends BaseAdapter {

    private LayoutInflater inflater = null;
    private List<SportActivity> activityList;

    public ActivityListAdapter(LayoutInflater inflater) {
        super();
        this.inflater = inflater;
    }

    @Override
    public int getCount() {
        return activityList == null ? 0 : activityList.size();
    }

    @Override
    public Object getItem(int position) {
        if (position < ((activityList == null ? 0 : activityList.size()) - 1))
            return activityList.get(position);

        Context context = inflater.getContext();
        return "Blah";
//        return String.format(context.getString(R.string.dialog_ellipsis), context.getString(R.string.Manage_workouts));
    }

    @Override
    public long getItemId(int position) {
        return 0;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        if (convertView == null) {
            convertView = inflater.inflate(android.R.layout.simple_spinner_dropdown_item, parent,
                    false);
        }

        TextView ret = (TextView) convertView.findViewById(android.R.id.text1);
        ret.setText(getItem(position).toString());
        return ret;
    }

    public int find(String name) {
        for (int i = 0; i < getCount(); i++) {
            if (name.contentEquals(getItem(i).toString()))
                return i;
        }
        return 0;
    }

    public void reload() {
        List<SportActivity> list = load(inflater.getContext());
        if (list == null) {
            activityList = new ArrayList<SportActivity>();
        } else {
            activityList = list;
//            workoutList = new String[list.length];
//            int index = 0;
//            for (String s : list) {
//                workoutList[index++] = s.substring(0, s.lastIndexOf('.'));
            }

        this.notifyDataSetChanged();
    }

    public static List<SportActivity> load(Context ctx) {
        SqlLogger logger = new SqlLogger();
        return logger.getSportActivities();
//        File f = ctx.getDir(WorkoutSerializer.WORKOUTS_DIR, 0);
//        return f.list(new FilenameFilter() {
//            @Override
//            public boolean accept(File dir, String filename) {
//                return filename.endsWith(".json");
//            }
//        });
    }
}
