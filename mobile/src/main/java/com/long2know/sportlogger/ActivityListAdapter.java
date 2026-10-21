package com.long2know.sportlogger;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.TextView;

import com.long2know.sportlogger.history.PhoneActivityDataSource.ActivityListItem;

import java.util.ArrayList;
import java.util.List;

public final class ActivityListAdapter extends BaseAdapter {
    private final LayoutInflater inflater;
    private List<ActivityListItem> activities = new ArrayList<>();

    public ActivityListAdapter(LayoutInflater inflater) {
        this.inflater = inflater;
    }

    public void setActivities(List<ActivityListItem> activities) {
        this.activities = new ArrayList<>(activities);
        notifyDataSetChanged();
    }

    @Override
    public int getCount() {
        return activities.size();
    }

    @Override
    public ActivityListItem getItem(int position) {
        return activities.get(position);
    }

    @Override
    public long getItemId(int position) {
        return getItem(position).getStorageId().hashCode();
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        ViewHolder holder;
        if (convertView == null) {
            convertView = inflater.inflate(R.layout.activity_history_item, parent, false);
            holder = new ViewHolder(
                    convertView.findViewById(R.id.activity_title),
                    convertView.findViewById(R.id.activity_start),
                    convertView.findViewById(R.id.activity_metrics),
                    convertView.findViewById(R.id.activity_transfer_state));
            convertView.setTag(holder);
        } else {
            holder = (ViewHolder) convertView.getTag();
        }

        ActivityListItem item = getItem(position);
        holder.title.setText(ActivityTextFormatter.title(inflater.getContext(), item));
        holder.start.setText(ActivityTextFormatter.dateTime(inflater.getContext(), item));
        holder.metrics.setText(ActivityTextFormatter.metrics(inflater.getContext(), item));
        holder.transferState.setText(
                ActivityTextFormatter.transferState(inflater.getContext(), item));
        convertView.setContentDescription(
                inflater.getContext().getString(
                        R.string.activity_row_content_description,
                        holder.title.getText(),
                        holder.start.getText(),
                        holder.metrics.getText(),
                        holder.transferState.getText()));
        return convertView;
    }

    private static final class ViewHolder {
        private final TextView title;
        private final TextView start;
        private final TextView metrics;
        private final TextView transferState;

        private ViewHolder(
                TextView title,
                TextView start,
                TextView metrics,
                TextView transferState) {
            this.title = title;
            this.start = start;
            this.metrics = metrics;
            this.transferState = transferState;
        }
    }
}
