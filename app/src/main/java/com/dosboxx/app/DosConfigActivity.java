package com.dosboxx.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class DosConfigActivity extends Activity {

    private String[] sections;
    private ListView sectionList;
    private LinearLayout propertyContainer;
    private String currentSection;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        sections = DosConfig.getSections();
        if (sections == null || sections.length == 0) {
            Toast.makeText(this, "No configuration sections found.", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setBackgroundColor(0xFF101418);

        sectionList = new ListView(this);
        sectionList.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        sectionList.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, sections));
        sectionList.setOnItemClickListener((parent, view, position, id) -> {
            loadSection(sections[position]);
        });
        root.addView(sectionList);

        View divider = new View(this);
        divider.setLayoutParams(new LinearLayout.LayoutParams(dp(1), ViewGroup.LayoutParams.MATCH_PARENT));
        divider.setBackgroundColor(Color.GRAY);
        root.addView(divider);

        LinearLayout rightPane = new LinearLayout(this);
        rightPane.setOrientation(LinearLayout.VERTICAL);
        rightPane.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 3f));

        ScrollView scroll = new ScrollView(this);
        scroll.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        propertyContainer = new LinearLayout(this);
        propertyContainer.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        propertyContainer.setPadding(pad, pad, pad, pad);
        scroll.addView(propertyContainer);
        rightPane.addView(scroll);

        LinearLayout footer = new LinearLayout(this);
        footer.setOrientation(LinearLayout.HORIZONTAL);
        footer.setPadding(pad, pad, pad, pad);
        footer.setGravity(Gravity.END);

        Button saveBtn = new Button(this);
        saveBtn.setText("Save to .conf");
        saveBtn.setOnClickListener(v -> {
            if (DosConfig.saveConfig()) {
                Toast.makeText(this, "Configuration saved.", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "Failed to save configuration.", Toast.LENGTH_LONG).show();
            }
        });
        footer.addView(saveBtn);

        Button closeBtn = new Button(this);
        closeBtn.setText("Close");
        closeBtn.setOnClickListener(v -> finish());
        footer.addView(closeBtn);

        rightPane.addView(footer);
        root.addView(rightPane);

        setContentView(root);

        // Auto-select first section or requested one
        int select = getIntent().getIntExtra("select", -1);
        if (select >= 0 && select < sections.length) {
            loadSection(sections[select]);
        } else {
            loadSection(sections[0]);
        }
    }

    private void loadSection(String section) {
        currentSection = section;
        propertyContainer.removeAllViews();

        TextView title = new TextView(this);
        title.setText("[" + section + "]");
        title.setTextSize(20);
        title.setTextColor(Color.WHITE);
        title.setTypeface(null, Typeface.BOLD);
        title.setPadding(0, 0, 0, dp(16));
        propertyContainer.addView(title);

        try {
            String json = DosConfig.getSectionProperties(section);
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.getJSONObject(i);
                addPropertyView(obj);
            }
        } catch (Exception e) {
            TextView err = new TextView(this);
            err.setText("Error loading properties: " + e.getMessage());
            err.setTextColor(Color.RED);
            propertyContainer.addView(err);
        }
    }

    private void addPropertyView(JSONObject p) throws Exception {
        String name = p.getString("name");
        String value = p.getString("value");
        String help = p.getString("help");
        JSONArray values = p.getJSONArray("values");

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, dp(8), 0, dp(8));

        TextView label = new TextView(this);
        label.setText(name);
        label.setTextColor(0xFF7FB8FF);
        label.setTextSize(16);
        row.addView(label);

        if (values.length() > 0) {
            // Enum / suggested values
            List<String> options = new ArrayList<>();
            int selected = -1;
            for (int i = 0; i < values.length(); i++) {
                String opt = values.getString(i);
                options.add(opt);
                if (opt.equalsIgnoreCase(value)) selected = i;
            }
            if (selected == -1) {
                options.add(0, value);
                selected = 0;
            }

            Spinner spinner = new Spinner(this);
            spinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, options));
            spinner.setSelection(selected);
            // To avoid immediate trigger on load
            final int fSelected = selected;
            spinner.post(() -> {
                spinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
                    @Override
                    public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                        if (position != fSelected) {
                            updateProperty(name, options.get(position));
                        }
                    }
                    @Override public void onNothingSelected(android.widget.AdapterView<?> parent) {}
                });
            });
            row.addView(spinner);
        } else if (isBoolean(value)) {
            CheckBox cb = new CheckBox(this);
            cb.setChecked(parseBoolean(value));
            cb.setOnCheckedChangeListener((buttonView, isChecked) -> {
                updateProperty(name, isChecked ? "true" : "false");
            });
            row.addView(cb);
        } else {
            EditText edit = new EditText(this);
            edit.setText(value);
            edit.setSingleLine(true);
            edit.setTextColor(Color.WHITE);
            edit.setOnFocusChangeListener((v, hasFocus) -> {
                if (!hasFocus) {
                    updateProperty(name, edit.getText().toString());
                }
            });
            row.addView(edit);
        }

        if (!help.isEmpty()) {
            TextView helpView = new TextView(this);
            helpView.setText(help);
            helpView.setTextSize(12);
            helpView.setTextColor(0xFFA0A0A0);
            helpView.setPadding(dp(4), dp(4), 0, 0);
            row.addView(helpView);
        }

        propertyContainer.addView(row);
    }

    private void updateProperty(String name, String value) {
        if (DosConfig.setProperty(currentSection, name, value)) {
            // Success
        } else {
            Toast.makeText(this, "Failed to set " + name, Toast.LENGTH_SHORT).show();
        }
    }

    private boolean isBoolean(String v) {
        return v.equalsIgnoreCase("true") || v.equalsIgnoreCase("false") || v.equalsIgnoreCase("on") || v.equalsIgnoreCase("off");
    }

    private boolean parseBoolean(String v) {
        return v.equalsIgnoreCase("true") || v.equalsIgnoreCase("on");
    }

    private int dp(int px) {
        return (int) (px * getResources().getDisplayMetrics().density);
    }
}
